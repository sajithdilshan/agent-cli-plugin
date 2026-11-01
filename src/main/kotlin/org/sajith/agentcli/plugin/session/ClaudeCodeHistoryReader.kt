package org.sajith.agentcli.plugin.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.StringReader

object ClaudeCodeHistoryReader {
    private val LOG = Logger.getInstance(ClaudeCodeHistoryReader::class.java)
    private const val MAX_LINES_FOR_FIRST_MESSAGE = 200

    /** Tags Claude Code wraps around injected (non-user-typed) "user" entries. */
    private val SYSTEM_TAG_PREFIXES =
        listOf(
            "<command-name>",
            "<command-message>",
            "<command-args>",
            "<local-command-",
            "<system-reminder>",
            "<task-notification>",
            "<bash-input>",
            "<bash-stdout>",
            "<bash-stderr>",
            "<user-prompt-submit-hook>",
            "Caveat:",
        )
    private val COMMAND_NAME_REGEX = Regex("<command-name>\\s*(.*?)\\s*</command-name>", RegexOption.DOT_MATCHES_ALL)
    private val COMMAND_ARGS_REGEX = Regex("<command-args>\\s*(.*?)\\s*</command-args>", RegexOption.DOT_MATCHES_ALL)

    internal fun isSystemGenerated(text: String): Boolean {
        val trimmed = text.trimStart()
        return SYSTEM_TAG_PREFIXES.any { trimmed.startsWith(it) }
    }

    /** Turns a `<command-name>` entry into "/cmd args"; other system entries yield "". */
    internal fun commandDisplayText(text: String): String {
        val name =
            COMMAND_NAME_REGEX
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
        if (name.isEmpty()) return ""
        val args =
            COMMAND_ARGS_REGEX
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
        return if (args.isEmpty()) name else "$name $args"
    }

    fun readHistory(
        projectPath: String,
        agentHome: File? = null,
    ): List<HistoricalSession> {
        val claudeDir = (agentHome ?: File(System.getProperty("user.home"), ".claude")).resolve("projects")
        if (!claudeDir.exists()) return emptyList()

        val encodedPath = SessionPathResolver.encodeClaudeProjectPath(projectPath)
        val projectDir = SessionPathResolver.resolveProjectDirectory(claudeDir, encodedPath) ?: return emptyList()
        val jsonlFiles = projectDir.listFiles { file -> file.extension == "jsonl" } ?: emptyArray()

        return HistoryReaderUtils.parseSessionsInParallel(
            files = jsonlFiles,
            sourceName = "AgentCLI",
            logger = LOG,
            parser = ::parseSessionFile,
        )
    }

    /**
     * Single-pass parse: reads the file once, extracting both first user message and custom title.
     * Uses GSON streaming (JsonReader) with skipValue() to avoid building full JSON trees.
     * Uses file modification time as timestamp.
     */
    internal fun parseSessionFile(file: File): HistoricalSession {
        val sessionId = file.nameWithoutExtension
        var firstUserMessage = ""
        var customTitle = ""
        var fallbackMessage = ""
        var linesScanned = 0

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue

                // Custom title: can appear on any line, use streaming parse
                if (customTitle.isEmpty() && line.contains("\"custom-title\"")) {
                    customTitle = extractFieldStreaming(line, "customTitle")
                    if (customTitle.isNotEmpty()) break // title found — no need for firstMessage
                }

                // First user message: only near the top, not needed if we already have a title.
                // Skip system-generated entries (slash commands, caveats, reminders) so the
                // actual prompt typed by the user is picked up.
                if (firstUserMessage.isEmpty() &&
                    linesScanned < MAX_LINES_FOR_FIRST_MESSAGE &&
                    line.contains("\"type\":\"user\"")
                ) {
                    val text = extractUserMessageStreaming(line)
                    if (text.isNotBlank()) {
                        if (isSystemGenerated(text)) {
                            if (fallbackMessage.isEmpty()) fallbackMessage = commandDisplayText(text)
                        } else {
                            firstUserMessage = text
                        }
                    }
                }

                linesScanned++
            }
        }
        if (firstUserMessage.isEmpty()) firstUserMessage = fallbackMessage

        val timestamp = HistoryReaderUtils.fileTimestamp(file)

        return HistoricalSession(
            sessionId = sessionId,
            customTitle = customTitle,
            firstMessage = firstUserMessage,
            timestamp = timestamp,
        )
    }

    /**
     * Extracts a single top-level string field from a JSON line using GSON streaming.
     * Skips all other fields without allocating objects — O(1) memory for non-target fields.
     */
    private fun extractFieldStreaming(
        line: String,
        targetKey: String,
    ): String {
        try {
            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == targetKey && reader.peek() == JsonToken.STRING) {
                        return reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }

    /**
     * Extracts the user message text using GSON streaming.
     * Verifies type=="user" and extracts the message content without building a full JsonObject,
     * falling back to tree parse only for complex nested message structures.
     */
    private fun extractUserMessageStreaming(line: String): String {
        try {
            var type: String? = null
            var messageRaw: String? = null
            var isMessageComplex = false
            var isMeta = false

            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "type" -> type = reader.nextString()
                        "isMeta" -> {
                            if (reader.peek() == JsonToken.BOOLEAN) isMeta = reader.nextBoolean() else reader.skipValue()
                        }
                        "message" -> {
                            if (reader.peek() == JsonToken.STRING) {
                                messageRaw = reader.nextString()
                            } else {
                                // Nested object/array — fall back to tree parse for this field
                                isMessageComplex = true
                                reader.skipValue()
                            }
                        }
                        "display" -> {
                            if (messageRaw == null && reader.peek() == JsonToken.STRING) {
                                messageRaw = reader.nextString()
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
            }

            if (type != "user" || isMeta) return ""
            if (messageRaw != null) return messageRaw

            // Complex message structure — use tree parse (rare case)
            if (isMessageComplex) {
                val obj = JsonParser.parseString(line).asJsonObject
                return extractUserMessage(obj)
            }
        } catch (_: Exception) {
        }
        return ""
    }

    /**
     * Extracts user message text from a JSONL entry.
     * The "message" field can be:
     *   - a string (simple message)
     *   - an object with { role, content } where content is either:
     *     - a string
     *     - a list of content blocks with { type: "text", text: "..." }
     */
    private fun extractUserMessage(obj: JsonObject): String {
        val messageEl = obj.get("message") ?: return obj.get("display")?.asString ?: ""

        // Simple string message
        if (messageEl.isJsonPrimitive) {
            return messageEl.asString
        }

        // Nested message object
        if (messageEl.isJsonObject) {
            val msgObj = messageEl.asJsonObject
            val content = msgObj.get("content") ?: return ""

            // content is a plain string
            if (content.isJsonPrimitive) {
                return content.asString
            }

            // content is an array of content blocks
            if (content.isJsonArray) {
                for (block in content.asJsonArray) {
                    if (block.isJsonObject) {
                        val blockObj = block.asJsonObject
                        val blockType = blockObj.get("type")?.asString
                        if (blockType == "text") {
                            val text = blockObj.get("text")?.asString ?: continue
                            if (text.isNotBlank()) return text
                        }
                    }
                }
            }
        }

        return ""
    }
}
