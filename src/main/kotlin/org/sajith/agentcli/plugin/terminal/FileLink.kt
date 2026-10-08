package org.sajith.agentcli.plugin.terminal

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A file location from an `agentcli://open?path=<abs>&line=<1-based>` link, the
 * links the bundled Claude mod draws under file-touching tool calls.
 */
data class FileLink(
    val path: String,
    val line: Int?,
) {
    companion object {
        const val SCHEME = "agentcli"

        fun parse(uri: URI): FileLink? {
            if (uri.scheme?.lowercase() != SCHEME || uri.host != "open") return null
            val params =
                (uri.rawQuery ?: return null)
                    .split('&')
                    .mapNotNull { pair ->
                        val parts = pair.split('=', limit = 2)
                        if (parts.size != 2) return@mapNotNull null
                        decode(parts[0]) to decode(parts[1])
                    }.toMap()
            val path = params["path"]?.takeIf { it.isNotBlank() } ?: return null
            val line = params["line"]?.toIntOrNull()?.takeIf { it > 0 }
            return FileLink(path, line)
        }

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
