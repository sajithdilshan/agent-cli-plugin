package org.sajith.agentcli.plugin.notify

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes the notify shell script to the user's home and merges hook entries into each agent's
 * config file.
 *
 * Idempotent: every entry we write carries a sentinel tag `"$SENTINEL_KEY": "$SENTINEL_VALUE"`
 * inside the inner hook object so re-running install/uninstall can locate and replace/remove just
 * our entries without touching anything else the user has configured by hand.
 */
object HookInstaller {
    private val LOG = Logger.getInstance(HookInstaller::class.java)
    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    const val SENTINEL_KEY = "agent_cli_plugin"
    const val SENTINEL_VALUE = "notify-hook"

    private val userHome: Path get() = Path.of(System.getProperty("user.home"))

    private val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("win")

    private val scriptResourceName: String get() = if (isWindows) "notify.ps1" else "notify.sh"

    private val scriptPath: Path get() = userHome.resolve(".agent-cli-plugin").resolve(scriptResourceName)

    /**
     * The command the hook entry should run. Paths get JSON-encoded by Gson so Windows
     * backslashes are handled automatically when the config is serialized.
     */
    private fun hookCommand(
        script: Path,
        scriptEvent: String,
        agent: String,
    ): String {
        val scriptString = script.toString()
        return if (isWindows) {
            "powershell -NoProfile -ExecutionPolicy Bypass -File \"$scriptString\" $scriptEvent $agent"
        } else {
            "\"$scriptString\" $scriptEvent $agent"
        }
    }

    data class PreviewEntry(
        val file: Path,
        val currentContent: String,
        val newContent: String,
        val existed: Boolean,
    )

    /** Pre-compute what install() will write without touching the filesystem. */
    fun preview(): List<PreviewEntry> {
        val result = mutableListOf<PreviewEntry>()
        val script = scriptPath
        for ((file, agent, events) in configs()) {
            val existing = if (Files.isRegularFile(file)) Files.readString(file) else "{}"
            val merged = applyInstall(existing, agent, events, script)
            result.add(PreviewEntry(file, existing, merged, Files.isRegularFile(file)))
        }
        return result
    }

    fun install(): InstallResult {
        return try {
            val script = writeScript()
            val configs = configs()
            val backups = mutableListOf<Path>()
            for ((file, agent, events) in configs) {
                Files.createDirectories(file.parent)
                val existed = Files.isRegularFile(file)
                val existing = if (existed) Files.readString(file) else "{}"
                val merged = applyInstall(existing, agent, events, script)
                if (merged == existing) continue // nothing to do
                if (existed) {
                    backupOf(file)?.let { backups.add(it) }
                }
                writeAtomic(file, merged)
            }
            InstallResult(
                scriptPath = script,
                scriptDeleted = false,
                configs = configs.map { it.file },
                backups = backups,
                error = null,
            )
        } catch (t: Throwable) {
            LOG.warn("[AgentCLI] hook install failed", t)
            InstallResult(
                scriptPath = scriptPath,
                scriptDeleted = false,
                configs = emptyList(),
                backups = emptyList(),
                error = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    fun uninstall(): InstallResult {
        return try {
            val touched = mutableListOf<Path>()
            val backups = mutableListOf<Path>()
            for ((file, _, _) in configs()) {
                if (!Files.isRegularFile(file)) continue
                val existing = Files.readString(file)
                val cleaned = applyUninstall(existing)
                if (cleaned != existing) {
                    backupOf(file)?.let { backups.add(it) }
                    writeAtomic(file, cleaned)
                    touched.add(file)
                }
            }
            val scriptDeleted = deleteScript()
            InstallResult(
                scriptPath = scriptPath,
                scriptDeleted = scriptDeleted,
                configs = touched,
                backups = backups,
                error = null,
            )
        } catch (t: Throwable) {
            LOG.warn("[AgentCLI] hook uninstall failed", t)
            InstallResult(
                scriptPath = scriptPath,
                scriptDeleted = false,
                configs = emptyList(),
                backups = emptyList(),
                error = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun deleteScript(): Boolean {
        val target = scriptPath
        val deleted =
            try {
                Files.deleteIfExists(target)
            } catch (t: Throwable) {
                LOG.warn("[AgentCLI] could not delete notify script $target", t)
                false
            }
        val parent = target.parent
        if (parent != null && Files.isDirectory(parent)) {
            runCatching {
                Files.newDirectoryStream(parent).use { stream ->
                    if (!stream.iterator().hasNext()) Files.delete(parent)
                }
            }.onFailure { LOG.debug("[AgentCLI] leaving $parent in place", it) }
        }
        return deleted
    }

    enum class Status { NOT_INSTALLED, INSTALLED, PARTIAL }

    /**
     * Inspect the user's config files and report whether our hooks are installed.
     * - NOT_INSTALLED: no managed entries anywhere
     * - INSTALLED: every config file has our entries for every event we own
     * - PARTIAL: some managed entries exist but not all — Install and Uninstall both make sense
     */
    fun status(): Status {
        var anyOurs = false
        var allOurs = true
        for ((file, _, events) in configs()) {
            val existing = if (Files.isRegularFile(file)) Files.readString(file) else null
            val root =
                existing?.let {
                    runCatching { JsonParser.parseString(it) }.getOrNull()
                        ?.takeIf { el -> el.isJsonObject }
                        ?.asJsonObject
                }
            val hooks = root?.getAsJsonObject("hooks")
            for (spec in events) {
                val arr = hooks?.getAsJsonArray(spec.eventName)
                val hasOurs = arr?.any { isOursGroup(it) } == true
                if (hasOurs) anyOurs = true else allOurs = false
            }
        }
        return when {
            !anyOurs -> Status.NOT_INSTALLED
            allOurs -> Status.INSTALLED
            else -> Status.PARTIAL
        }
    }

    /**
     * Copies `file` to a timestamped sibling `<name>.bak` (or `<name>.<timestamp>.bak` if a backup
     * already exists) so the user can recover from a bad merge. Returns the backup path, or null
     * if the backup could not be created (logged but non-fatal — the user would rather have the
     * install proceed than fail on a read-only parent directory).
     */
    private fun backupOf(file: Path): Path? {
        if (!Files.isRegularFile(file)) return null
        return try {
            val base = file.resolveSibling(file.fileName.toString() + ".bak")
            val target =
                if (!Files.exists(base)) {
                    base
                } else {
                    val stamp = java.time.LocalDateTime.now().format(BACKUP_TIMESTAMP)
                    file.resolveSibling("${file.fileName}.$stamp.bak")
                }
            Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
            target
        } catch (t: Throwable) {
            LOG.warn("[AgentCLI] could not back up $file", t)
            null
        }
    }

    private val BACKUP_TIMESTAMP = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    // --- internals ---------------------------------------------------------

    private data class AgentConfig(
        val file: Path,
        val agent: String,
        val events: List<EventSpec>,
    )

    private data class EventSpec(
        val eventName: String,
        val matcher: String,
        val scriptEvent: String,
    )

    private fun configs(): List<AgentConfig> =
        listOf(
            AgentConfig(
                file = userHome.resolve(".claude/settings.json"),
                agent = "claude",
                events =
                    listOf(
                        EventSpec("Notification", "permission_prompt|idle_prompt", "set"),
                        EventSpec("Stop", "", "clear"),
                        EventSpec("SessionEnd", "", "clear"),
                    ),
            ),
            AgentConfig(
                file = userHome.resolve(".gemini/settings.json"),
                agent = "gemini",
                events =
                    listOf(
                        EventSpec("Notification", "*", "set"),
                        EventSpec("AfterAgent", "*", "clear"),
                        EventSpec("SessionEnd", "*", "clear"),
                    ),
            ),
            AgentConfig(
                file = userHome.resolve(".codex/hooks.json"),
                agent = "codex",
                events =
                    listOf(
                        EventSpec("PermissionRequest", "", "set"),
                        EventSpec("Stop", "", "clear"),
                    ),
            ),
        )

    private fun writeScript(): Path {
        val target = scriptPath
        Files.createDirectories(target.parent)
        Files.deleteIfExists(target)
        val resource = "/notify/$scriptResourceName"
        val source: InputStream =
            requireNotNull(HookInstaller::class.java.getResourceAsStream(resource)) {
                "$scriptResourceName resource missing from plugin jar"
            }
        source.use { Files.copy(it, target) }
        if (!isWindows) {
            runCatching {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
            }.onFailure {
                runCatching {
                    val perms = Files.getPosixFilePermissions(target).toMutableSet()
                    perms.add(PosixFilePermission.OWNER_EXECUTE)
                    Files.setPosixFilePermissions(target, perms)
                }.onFailure {
                    target.toFile().setExecutable(true, false)
                }
            }
        }
        return target
    }

    private fun applyInstall(
        existing: String,
        agent: String,
        events: List<EventSpec>,
        script: Path,
    ): String {
        val root =
            runCatching { JsonParser.parseString(existing) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: JsonObject()

        val hooksNode =
            root.getAsJsonObject("hooks")
                ?: JsonObject().also { root.add("hooks", it) }

        for (spec in events) {
            val arr =
                hooksNode.getAsJsonArray(spec.eventName)
                    ?: JsonArray().also { hooksNode.add(spec.eventName, it) }

            // Drop any pre-existing entry we previously wrote.
            val remaining = JsonArray()
            for (item in arr) {
                if (!isOursGroup(item)) remaining.add(item)
            }
            hooksNode.add(spec.eventName, remaining)

            val inner =
                JsonObject().apply {
                    addProperty(SENTINEL_KEY, SENTINEL_VALUE)
                    addProperty("type", "command")
                    addProperty("command", hookCommand(script, spec.scriptEvent, agent))
                }
            val innerArr = JsonArray().apply { add(inner) }
            val entry =
                JsonObject().apply {
                    addProperty("matcher", spec.matcher)
                    add("hooks", innerArr)
                }
            remaining.add(entry)
        }
        return GSON.toJson(root) + "\n"
    }

    private fun applyUninstall(existing: String): String {
        val root =
            runCatching { JsonParser.parseString(existing) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return existing
        val hooks = root.getAsJsonObject("hooks") ?: return existing

        val emptiedEvents = mutableListOf<String>()
        for (eventName in hooks.keySet().toList()) {
            val arr = hooks.getAsJsonArray(eventName) ?: continue
            val remaining = JsonArray()
            for (item in arr) {
                if (!isOursGroup(item)) remaining.add(item)
            }
            if (remaining.size() == 0) {
                emptiedEvents.add(eventName)
            } else {
                hooks.add(eventName, remaining)
            }
        }
        emptiedEvents.forEach { hooks.remove(it) }
        if (hooks.keySet().isEmpty()) root.remove("hooks")
        return GSON.toJson(root) + "\n"
    }

    private fun isOursGroup(element: com.google.gson.JsonElement): Boolean {
        if (!element.isJsonObject) return false
        val inner = element.asJsonObject.getAsJsonArray("hooks") ?: return false
        return inner.any { item ->
            item.isJsonObject &&
                item.asJsonObject.has(SENTINEL_KEY) &&
                item.asJsonObject.get(SENTINEL_KEY).asString == SENTINEL_VALUE
        }
    }

    private fun writeAtomic(
        file: Path,
        content: String,
    ) {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    data class InstallResult(
        val scriptPath: Path,
        val scriptDeleted: Boolean,
        val configs: List<Path>,
        val backups: List<Path>,
        val error: String?,
    ) {
        val success: Boolean get() = error == null
    }
}
