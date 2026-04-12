package org.sajith.agentcli.plugin.session

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object CodexHistoryReader {
    private val LOG = Logger.getInstance(CodexHistoryReader::class.java)

    private val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private data class IndexEntry(
        val threadName: String?,
        val updatedAt: String?,
    )

    fun readHistory(projectPath: String): List<HistoricalSession> {
        val codexDir = File(System.getProperty("user.home"), ".codex")
        if (!codexDir.exists()) return emptyList()

        val sessionsDir = codexDir.resolve("sessions")
        if (!sessionsDir.exists() || !sessionsDir.isDirectory) return emptyList()

        val index = readSessionIndex(codexDir)
        val sessionFiles = sessionsDir.walkTopDown().filter { it.extension == "jsonl" }.toList().toTypedArray()

        return HistoryReaderUtils.parseSessionsInParallel(
            files = sessionFiles,
            sourceName = "Codex",
            logger = LOG,
            parser = { file -> parseSessionFile(file, projectPath, index) },
        )
    }

    private fun readSessionIndex(codexDir: File): Map<String, IndexEntry> {
        val indexFile = codexDir.resolve("session_index.jsonl")
        if (!indexFile.exists()) return emptyMap()

        val index = mutableMapOf<String, IndexEntry>()
        try {
            indexFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val entry = parseIndexLine(line) ?: continue
                    index[entry.first] = entry.second
                }
            }
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] Failed to read Codex session index", e)
        }
        return index
    }

    private fun parseIndexLine(line: String): Pair<String, IndexEntry>? {
        try {
            var id: String? = null
            var threadName: String? = null
            var updatedAt: String? = null

            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "id" -> id = reader.nextString()
                        "thread_name" -> {
                            if (reader.peek() == JsonToken.STRING) {
                                threadName = reader.nextString()
                            } else {
                                reader.skipValue()
                            }
                        }
                        "updated_at" -> {
                            if (reader.peek() == JsonToken.STRING) {
                                updatedAt = reader.nextString()
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
            }
            val sessionId = id ?: return null
            return sessionId to IndexEntry(threadName, updatedAt)
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] Failed to parse Codex index line", e)
            return null
        }
    }

    /**
     * Extracts the UUID session ID from a Codex session filename.
     * Filenames follow the pattern: rollout-YYYY-MM-DDTHH-mm-ss-<uuid>.jsonl
     */
    private fun extractSessionId(filename: String): String? {
        val match = UUID_REGEX.find(filename)
        return match?.value
    }

    /**
     * Parses a single Codex session file. Returns null if the session belongs to a different project.
     *
     * 1. Extracts session ID from filename
     * 2. Reads first line (session_meta) for cwd — filters by project path
     * 3. If index has a thread_name, uses it as title and skips further reading
     * 4. Otherwise scans for first user_message event
     */
    private fun parseSessionFile(
        file: File,
        projectPath: String,
        index: Map<String, IndexEntry>,
    ): HistoricalSession? {
        val sessionId = extractSessionId(file.nameWithoutExtension) ?: return null

        var cwd: String? = null
        var firstUserMessage = ""
        val indexEntry = index[sessionId]
        val hasTitle = !indexEntry?.threadName.isNullOrBlank()

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue

                // First: extract cwd from session_meta
                if (cwd == null && line.contains("\"session_meta\"")) {
                    cwd = extractCwd(line)
                    val resolvedCwd = cwd
                    if (resolvedCwd != null && !resolvedCwd.trimEnd('/').equals(projectPath.trimEnd('/'), ignoreCase = true)) {
                        return null // Different project
                    }
                    if (hasTitle) break // Have title from index, no need to read further
                    continue
                }

                // If no title from index, find first user message
                if (!hasTitle && firstUserMessage.isEmpty() && line.contains("\"user_message\"")) {
                    firstUserMessage = extractUserMessage(line)
                    if (firstUserMessage.isNotEmpty()) break
                }
            }
        }

        // If we never found session_meta or cwd didn't match, skip
        if (cwd == null) return null

        val timestamp =
            indexEntry?.updatedAt?.let { parseTimestamp(it) }
                ?: HistoryReaderUtils.fileTimestamp(file)

        return HistoricalSession(
            sessionId = sessionId,
            customTitle = indexEntry?.threadName ?: "",
            firstMessage = firstUserMessage,
            timestamp = timestamp,
        )
    }

    /**
     * Extracts payload.cwd from a session_meta JSONL line using streaming.
     */
    private fun extractCwd(line: String): String? {
        try {
            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "payload" -> {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    if (reader.nextName() == "cwd" && reader.peek() == JsonToken.STRING) {
                                        return reader.nextString()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Extracts payload.message from an event_msg line with payload.type == "user_message".
     */
    private fun extractUserMessage(line: String): String {
        try {
            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "payload" -> {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                return readUserMessagePayload(reader)
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }

    private fun readUserMessagePayload(reader: JsonReader): String {
        var type: String? = null
        var message: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> {
                    if (reader.peek() == JsonToken.STRING) {
                        type = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                "message" -> {
                    if (reader.peek() == JsonToken.STRING) {
                        message = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return if (type == "user_message" && !message.isNullOrBlank()) message else ""
    }

    private fun parseTimestamp(ts: String): LocalDateTime? {
        return try {
            val instant = Instant.parse(ts)
            LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the session file for a given session ID by scanning ~/.codex/sessions/ recursively.
     */
    fun findSessionFile(sessionId: String): File? {
        val sessionsDir = File(System.getProperty("user.home"), ".codex/sessions")
        if (!sessionsDir.exists()) return null
        return sessionsDir.walkTopDown().firstOrNull {
            it.extension == "jsonl" && it.nameWithoutExtension.endsWith(sessionId)
        }
    }

    /**
     * Removes an entry from session_index.jsonl by session ID.
     */
    fun removeFromIndex(sessionId: String): Boolean {
        val indexFile = File(System.getProperty("user.home"), ".codex/session_index.jsonl")
        if (!indexFile.exists()) return false

        try {
            val lines = indexFile.readLines()
            val filtered =
                lines.filter { line ->
                    if (line.isBlank()) return@filter true
                    val id = extractIdFromLine(line)
                    id != sessionId
                }
            if (filtered.size == lines.size) return false // Nothing removed
            indexFile.writeText(filtered.joinToString("\n") + if (filtered.isNotEmpty()) "\n" else "")
            return true
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] Failed to update Codex session index", e)
            return false
        }
    }

    private fun extractIdFromLine(line: String): String? {
        try {
            JsonReader(StringReader(line)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "id" && reader.peek() == JsonToken.STRING) {
                        return reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
