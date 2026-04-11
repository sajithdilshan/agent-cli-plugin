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

    fun encodeClaudeProjectPath(projectPath: String): String = projectPath.trimEnd('/').replace("/", "-")

    fun encodeCursorProjectPath(projectPath: String): String = projectPath.trimEnd('/').replace("/", "-").removePrefix("-")

    fun resolveProjectDirectory(
        baseDir: File,
        encodedPath: String,
    ): File? {
        val direct = baseDir.resolve(encodedPath)
        if (direct.exists() && direct.isDirectory) return direct
        return baseDir.listFiles { f -> f.isDirectory }?.firstOrNull { it.name == encodedPath }
    }

    fun resolveGeminiProjectName(
        geminiDir: File,
        projectPath: String,
    ): String? {
        val projects = loadGeminiProjects(geminiDir) ?: return null
        return projects[projectPath.trimEnd('/')]
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
