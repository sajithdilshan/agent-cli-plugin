package org.sajith.agentcli.plugin.session

import java.io.File

internal object SessionPathResolver {
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

    /** Expands a leading `~` or `~/` to the user's home directory. */
    fun expandHome(path: String): File {
        val home = System.getProperty("user.home")
        val expanded =
            when {
                path == "~" -> home
                path.startsWith("~/") || path.startsWith("~\\") -> home + path.substring(1)
                else -> path
            }
        return File(expanded)
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
}
