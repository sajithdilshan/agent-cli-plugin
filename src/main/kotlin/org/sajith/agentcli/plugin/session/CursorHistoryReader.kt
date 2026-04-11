package org.sajith.agentcli.plugin.session

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object CursorHistoryReader {
    private val LOG = Logger.getInstance(CursorHistoryReader::class.java)
    private val USER_QUERY_REGEX = Regex("<user_query>\\s*(.*?)\\s*</user_query>", RegexOption.DOT_MATCHES_ALL)

    fun readHistory(projectPath: String): List<HistoricalSession> {
        val cursorDir = File(System.getProperty("user.home"), ".cursor/projects")
        if (!cursorDir.exists()) return emptyList()

        val normalizedPath = projectPath.trimEnd('/')
        val encodedPath = normalizedPath.replace("/", "-").removePrefix("-")

        val projectDir = resolveProjectDirectory(cursorDir, encodedPath) ?: return emptyList()
        val transcriptsDir = projectDir.resolve("agent-transcripts")
        if (!transcriptsDir.exists()) return emptyList()

        val sessionDirs = transcriptsDir.listFiles { f -> f.isDirectory } ?: emptyArray()

        return sessionDirs.toList().parallelStream()
            .map { dir ->
                val jsonlFile = dir.resolve("${dir.name}.jsonl")
                if (!jsonlFile.exists()) return@map null
                try {
                    parseSessionFile(jsonlFile)
                } catch (e: Exception) {
                    LOG.warn("[Cursor] Failed to parse session file: ${jsonlFile.name}", e)
                    null
                }
            }
            .filter { it != null }
            .map { it!! }
            .toList()
            .sortedByDescending { it.timestamp }
    }

    private fun resolveProjectDirectory(
        cursorDir: File,
        encodedPath: String,
    ): File? {
        val direct = cursorDir.resolve(encodedPath)
        if (direct.exists() && direct.isDirectory) return direct

        return cursorDir.listFiles { f -> f.isDirectory }?.firstOrNull { it.name == encodedPath }
    }

    private fun parseSessionFile(file: File): HistoricalSession? {
        val sessionId = file.nameWithoutExtension
        var firstUserMessage = ""
        var linesScanned = 0

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                if (++linesScanned > 100) break

                if (line.contains("\"user\"")) {
                    val msg = extractUserMessageStreaming(line)
                    if (msg.isNotEmpty()) {
                        firstUserMessage = msg
                        break
                    }
                }
            }
        }

        val timestamp = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(file.lastModified()),
            ZoneId.systemDefault(),
        )

        return HistoricalSession(
            sessionId = sessionId,
            customTitle = "",
            firstMessage = firstUserMessage,
            timestamp = timestamp,
        )
    }

    /**
     * Uses GSON streaming to extract the message from a user line without full tree parse.
     * Falls back gracefully for complex nested message structures.
     */
    private fun extractUserMessageStreaming(line: String): String {
        try {
            var role: String? = null
            var message: String? = null
            var isMessageComplex = false

            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "role" -> role = reader.nextString()
                        "message" -> {
                            if (reader.peek() == JsonToken.STRING) {
                                message = reader.nextString()
                            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                message = extractContentFromMessageObject(reader)
                                isMessageComplex = true
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
            }

            if (role != "user") return ""
            return message?.let { stripUserQueryTags(it) } ?: ""
        } catch (_: Exception) {
        }
        return ""
    }

    /**
     * Streams through a nested message object { content: string | [{ type, text }] }
     */
    private fun extractContentFromMessageObject(reader: JsonReader): String? {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "content") {
                if (reader.peek() == JsonToken.STRING) {
                    return reader.nextString()
                }
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            var type: String? = null
                            var text: String? = null
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "type" -> type = reader.nextString()
                                    "text" -> text = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (type == "text" && !text.isNullOrBlank()) return text
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                    return null
                }
                reader.skipValue()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return null
    }

    private fun stripUserQueryTags(text: String): String {
        val match = USER_QUERY_REGEX.find(text)
        return (match?.groupValues?.get(1) ?: text).trim()
    }
}
