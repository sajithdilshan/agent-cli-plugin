package org.sajith.agentcli.plugin.session

import com.google.gson.JsonParser
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

        val sessions = mutableListOf<HistoricalSession>()

        for (file in jsonFiles) {
            try {
                val session = parseSessionFile(file)
                if (session != null) sessions.add(session)
            } catch (e: Exception) {
                LOG.warn("[Gemini] Failed to parse session file: ${file.name}", e)
            }
        }

        return sessions.sortedByDescending { it.timestamp }
    }

    private fun resolveProjectName(geminiDir: File, projectPath: String): String? {
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

    private fun parseSessionFile(file: File): HistoricalSession? {
        val root = JsonParser.parseString(file.readText()).asJsonObject

        val sessionId = root.get("sessionId")?.asString ?: return null
        val startTime = root.get("startTime")?.asString?.let { parseTimestamp(it) } ?: return null
        val messages = root.getAsJsonArray("messages") ?: return null

        var firstUserMessage = ""
        var messageCount = 0

        for (element in messages) {
            if (!element.isJsonObject) continue
            val msg = element.asJsonObject
            val type = msg.get("type")?.asString ?: continue

            if (type == "user") {
                messageCount++
                if (firstUserMessage.isEmpty()) {
                    firstUserMessage = extractUserMessage(msg)
                }
            }
        }

        return HistoricalSession(
            sessionId = sessionId,
            customTitle = "",
            firstMessage = firstUserMessage,
            timestamp = startTime,
            messageCount = messageCount
        )
    }

    private fun extractUserMessage(msg: com.google.gson.JsonObject): String {
        val content = msg.getAsJsonArray("content") ?: return ""
        for (block in content) {
            if (!block.isJsonObject) continue
            val text = block.asJsonObject.get("text")?.asString
            if (!text.isNullOrBlank()) return text
        }
        return ""
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
