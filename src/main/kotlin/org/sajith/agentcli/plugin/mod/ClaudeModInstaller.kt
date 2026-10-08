package org.sajith.agentcli.plugin.mod

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.NioFiles
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts the bundled Claude Code mod (`resources/claude-mod/agent-cli-links`) to the
 * IDE system directory so Claude sessions can load it via `CLAUDE_CODE_PLUGIN_DIRS`.
 * Only installed while enabled in settings; disabling it removes the extracted folder.
 *
 * Files are only rewritten when their content changed: Claude watches the folder and
 * hot-reloads the mod on every write.
 */
object ClaudeModInstaller {
    private val LOG = Logger.getInstance(ClaudeModInstaller::class.java)

    private const val MOD_NAME = "agent-cli-links"
    private const val RESOURCE_ROOT = "/claude-mod/$MOD_NAME"
    private val FILES =
        listOf(
            ".claude-plugin/plugin.json",
            "hooks/hooks.json",
            "hooks/register.tsx",
            "hooks/links.ts",
        )

    private val target: Path
        get() = Path.of(PathManager.getSystemPath(), "agent-cli-plugin", "claude-mod", MOD_NAME)

    @Volatile
    private var installed: Path? = null

    /** The extracted mod folder when the mod is enabled, extracting it on first use; null otherwise. */
    @Synchronized
    fun modDirIfEnabled(): Path? {
        if (!AgentCliSettings.getInstance().claudeFileLinksModEnabled) return null
        return installed ?: extract()?.also { installed = it }
    }

    @Synchronized
    fun uninstall() {
        installed = null
        try {
            if (Files.exists(target)) {
                NioFiles.deleteRecursively(target)
                LOG.info("[AgentCLI] Claude mod removed from $target")
            }
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] Failed to remove Claude mod at $target", e)
        }
    }

    private fun extract(): Path? =
        try {
            for (file in FILES) {
                val bytes =
                    ClaudeModInstaller::class.java.getResourceAsStream("$RESOURCE_ROOT/$file")?.use { it.readBytes() }
                        ?: error("missing bundled resource $RESOURCE_ROOT/$file")
                val dest = target.resolve(file)
                if (Files.exists(dest) && Files.readAllBytes(dest).contentEquals(bytes)) continue
                Files.createDirectories(dest.parent)
                Files.write(dest, bytes)
            }
            LOG.info("[AgentCLI] Claude mod installed at $target")
            target
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] Failed to install Claude mod", e)
            null
        }
}
