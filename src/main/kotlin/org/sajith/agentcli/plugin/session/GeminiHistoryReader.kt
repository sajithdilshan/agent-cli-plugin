package org.sajith.agentcli.plugin.session

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object GeminiHistoryReader {
    private val LOG = Logger.getInstance(GeminiHistoryReader::class.java)

    fun readHistory(projectPath: String): List<HistoricalSession> {
        val geminiDir = File(System.getProperty("user.home"), ".gemini")
        if (!geminiDir.exists()) return emptyList()

        val projectName = resolveProjectName(geminiDir, projectPath) ?: return emptyList()

        val chatsDir = geminiDir.resolve("tmp/$projectName/chats")
        if (!chatsDir.exists() || !chatsDir.isDirectory) return emptyList()

        val jsonFiles = chatsDir.listFiles { file -> file.extension == "json" } ?: emptyArray()

        return jsonFiles.toList().parallelStream()
            .map { file ->
                try {
                    parseSessionFile(file)
                } catch (e: Exception) {
                    LOG.warn("[Gemini] Failed to parse session file: ${file.name}", e)
                    null
                }
            }
            .filter { it != null }
            .map { it!! }
            .toList()
            .sortedByDescending { it.timestamp }
    }

    private fun resolveProjectName(
        geminiDir: File,
        projectPath: String,
    ): String? {
        val projectsFile = geminiDir.resolve("projects.json")
        if (!projectsFile.exists()) return null

        return try {
            val root = JsonParser.parseString(projectsFile.readText()).asJsonObject
            val projects = root.getAsJsonObject("projects") ?: return null
            val normalizedPath = projectPath.trimEnd('/')
            projects.get(normalizedPath)?.asString
        } catch (e: Exception) {
            LOG.warn("[Gemini] Failed to parse projects.json", e)
            null
        }
    }

    /**
     * Streams through the JSON file extracting only sessionId, startTime, and first user message.
     * Skips the rest of the messages array without building a full tree.
     */
    private fun parseSessionFile(file: File): HistoricalSession? {
        var sessionId: String? = null
        var startTime: String? = null
        var firstUserMessage = ""

        JsonReader(file.reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "sessionId" -> sessionId = reader.nextString()
                    "startTime" -> startTime = reader.nextString()
                    "messages" -> {
                        firstUserMessage = readFirstUserMessage(reader)
                    }
                    else -> reader.skipValue()
                }
            }
        }

        val ts = startTime?.let { parseTimestamp(it) } ?: return null

        return HistoricalSession(
            sessionId = sessionId ?: return null,
            customTitle = "",
            firstMessage = firstUserMessage,
            timestamp = ts,
        )
    }

    /**
     * Streams through the messages array, extracts the first user message text,
     * then skips the rest of the array.
     */
    private fun readFirstUserMessage(reader: JsonReader): String {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }

            var type: String? = null
            var text: String? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "type" -> type = reader.nextString()
                    "content" -> {
                        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                            text = readFirstTextBlock(reader)
                        } else {
                            reader.skipValue()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (type == "user" && !text.isNullOrBlank()) {
                // Skip remaining messages
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
                return text
            }
        }
        reader.endArray()
        return ""
    }

    /**
     * Streams through a content blocks array, returns the first non-blank text.
     */
    private fun readFirstTextBlock(reader: JsonReader): String? {
        var result: String? = null
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            var text: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "text" && reader.peek() == JsonToken.STRING) {
                    text = reader.nextString()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            if (result == null && !text.isNullOrBlank()) result = text
        }
        reader.endArray()
        return result
    }

    private fun parseTimestamp(ts: String): LocalDateTime? {
        return try {
            val instant = Instant.parse(ts)
            LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }
}
