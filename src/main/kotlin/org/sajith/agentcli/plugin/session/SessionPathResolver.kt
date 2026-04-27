package org.sajith.agentcli.plugin.session

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File

internal object SessionPathResolver {
    private val LOG = Logger.getInstance(SessionPathResolver::class.java)

    @Volatile
    private var cachedGeminiProjectsMtime: Long = -1L

    @Volatile
    private var cachedGeminiProjects: Map<String, String> = emptyMap()

    /**
     * Normalizes a filesystem path for cross-platform comparison. On Windows,
     * `project.basePath` from IntelliJ uses `/`, but CLI tools often store paths with `\`.
     * We flatten to `/`, drop trailing slashes, and lowercase the Windows drive letter.
     */
    fun normalizePath(path: String): String {
        val flat = path.replace('\\', '/').trimEnd('/')
        // Lowercase drive letter ("C:/..." -> "c:/...") so case-insensitive compares work
        return if (flat.length >= 2 && flat[1] == ':') flat[0].lowercaseChar() + flat.substring(1) else flat
    }

    fun encodeClaudeProjectPath(projectPath: String): String = normalizePath(projectPath).replace("/", "-")

    fun encodeCursorProjectPath(projectPath: String): String = normalizePath(projectPath).replace("/", "-").removePrefix("-")

    fun resolveProjectDirectory(
        baseDir: File,
        encodedPath: String,
    ): File? {
        val direct = baseDir.resolve(encodedPath)
        if (direct.exists() && direct.isDirectory) return direct
        // Fallback: case-insensitive match (Windows filesystems are typically case-insensitive but
        // the encoded string preserves original case, so match loosely).
        return baseDir.listFiles { f -> f.isDirectory }?.firstOrNull { it.name.equals(encodedPath, ignoreCase = true) }
    }

    fun resolveGeminiProjectName(
        geminiDir: File,
        projectPath: String,
    ): String? {
        val projects = loadGeminiProjects(geminiDir) ?: return null
        val needle = normalizePath(projectPath)
        // Gemini stores paths as the CLI saw them — could be `\`-separated on Windows. Normalize both sides.
        return projects.entries.firstOrNull { normalizePath(it.key) == needle }?.value
    }

    private fun loadGeminiProjects(geminiDir: File): Map<String, String>? {
        val projectsFile = geminiDir.resolve("projects.json")
        if (!projectsFile.exists()) return null

        val lastModified = projectsFile.lastModified()
        if (cachedGeminiProjectsMtime == lastModified) {
            return cachedGeminiProjects
        }

        return synchronized(this) {
            if (cachedGeminiProjectsMtime == lastModified) {
                return@synchronized cachedGeminiProjects
            }

            try {
                val root = JsonParser.parseString(projectsFile.readText()).asJsonObject
                val projectsJson = root.getAsJsonObject("projects") ?: return@synchronized emptyMap()
                val parsed =
                    projectsJson.entrySet().associate { (path, nameElement) ->
                        path to nameElement.asString
                    }
                cachedGeminiProjectsMtime = lastModified
                cachedGeminiProjects = parsed
                parsed
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] Failed to parse Gemini projects.json", e)
                null
            }
        }
    }
}
