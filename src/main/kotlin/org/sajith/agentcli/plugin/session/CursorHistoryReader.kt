package org.sajith.agentcli.plugin.session

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
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

        val sessions = mutableListOf<HistoricalSession>()

        val sessionDirs = transcriptsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in sessionDirs) {
            val jsonlFile = dir.resolve("${dir.name}.jsonl")
            if (!jsonlFile.exists()) continue
            try {
                val session = parseSessionFile(jsonlFile)
                if (session != null) sessions.add(session)
            } catch (e: Exception) {
                LOG.warn("[Cursor] Failed to parse session file: ${jsonlFile.name}", e)
            }
        }

        return sessions.sortedByDescending { it.timestamp }
    }

    private fun resolveProjectDirectory(cursorDir: File, encodedPath: String): File? {
        val direct = cursorDir.resolve(encodedPath)
        if (direct.exists() && direct.isDirectory) return direct

        return cursorDir.listFiles { f -> f.isDirectory }?.firstOrNull { it.name == encodedPath }
    }

    private fun parseSessionFile(file: File): HistoricalSession? {
        val sessionId = file.nameWithoutExtension
        var firstUserMessage = ""
        var timestamp: LocalDateTime? = null
        var messageCount = 0

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val obj = JsonParser.parseString(line).asJsonObject
                    val role = obj.get("role")?.asString ?: continue

                    if (role == "user") {
                        messageCount++

                        if (firstUserMessage.isEmpty()) {
                            firstUserMessage = extractUserMessage(obj)
                        }
                        if (timestamp == null) {
                            timestamp = getFileTimestamp(file)
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
        }

        val ts = timestamp ?: return null

        return HistoricalSession(
            sessionId = sessionId,
            customTitle = "",
            firstMessage = firstUserMessage,
            timestamp = ts,
            messageCount = messageCount
        )
    }

    private fun extractUserMessage(obj: com.google.gson.JsonObject): String {
        val messageEl = obj.get("message") ?: return ""

        if (messageEl.isJsonObject) {
            val content = messageEl.asJsonObject.get("content")
            if (content != null && content.isJsonArray) {
                for (block in content.asJsonArray) {
                    if (block.isJsonObject) {
                        val blockObj = block.asJsonObject
                        if (blockObj.get("type")?.asString == "text") {
                            val text = blockObj.get("text")?.asString ?: continue
                            if (text.isNotBlank()) return stripUserQueryTags(text)
                        }
                    }
                }
            }
            if (content != null && content.isJsonPrimitive) {
                return stripUserQueryTags(content.asString)
            }
        }

        if (messageEl.isJsonPrimitive) {
            return stripUserQueryTags(messageEl.asString)
        }

        return ""
    }

    private fun stripUserQueryTags(text: String): String {
        val match = USER_QUERY_REGEX.find(text)
        return (match?.groupValues?.get(1) ?: text).trim()
    }

    private fun getFileTimestamp(file: File): LocalDateTime? {
        return try {
            val millis = file.lastModified()
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }
}
