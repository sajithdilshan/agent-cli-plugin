package org.sajith.agentcli.plugin.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object ClaudeCodeHistoryReader {
    private val LOG = Logger.getInstance(ClaudeCodeHistoryReader::class.java)

    fun readHistory(projectPath: String): List<HistoricalSession> {
        val claudeDir = File(System.getProperty("user.home"), ".claude/projects")
        if (!claudeDir.exists()) return emptyList()

        val normalizedPath = projectPath.trimEnd('/')
        val encodedPath = normalizedPath.replace("/", "-")

        val projectDir = resolveProjectHistoryDirectory(claudeDir, encodedPath) ?: return emptyList()

        val jsonlFiles = projectDir.listFiles { file -> file.extension == "jsonl" } ?: emptyArray()

        val sessions = mutableListOf<HistoricalSession>()

        jsonlFiles.forEach { file ->
            try {
                val session = parseSessionFile(file)
                sessions.add(session)
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] failed to parse session file: ${file.name}", e)
            }
        }

        return sessions.sortedByDescending { it.timestamp }
    }

    private fun resolveProjectHistoryDirectory(
        claudeDir: File,
        encodedPath: String,
    ): File? {
        val direct = claudeDir.resolve(encodedPath)
        if (direct.exists() && direct.isDirectory) return direct

        return claudeDir.listFiles { f -> f.isDirectory }?.firstOrNull { it.name == encodedPath }
    }

    /**
     * Lightweight parse: reads only the first few lines to extract title and first user message.
     * Uses file modification time as timestamp to avoid scanning deep into large files.
     */
    private fun parseSessionFile(file: File): HistoricalSession {
        val sessionId = file.nameWithoutExtension

        // First user message: always near the top, scan first 50 lines
        var firstUserMessage = ""
        var linesScanned = 0
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                linesScanned++

                if (line.contains("\"type\":\"user\"")) {
                    try {
                        val obj = JsonParser.parseString(line).asJsonObject
                        if (obj.get("type")?.asString == "user") {
                            firstUserMessage = extractUserMessage(obj)
                            break
                        }
                    } catch (_: Exception) {
                    }
                }

                if (linesScanned >= 50) break
            }
        }

        // Custom title: can appear anywhere, search the file in parallel chunks
        val customTitle = findCustomTitleParallel(file)

        // Use file modification time — fast and avoids parsing timestamps from JSON
        val timestamp =
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(file.lastModified()),
                ZoneId.systemDefault(),
            )

        return HistoricalSession(
            sessionId = sessionId,
            customTitle = customTitle,
            firstMessage = firstUserMessage,
            timestamp = timestamp,
            messageCount = 0,
        )
    }

    private fun findCustomTitleParallel(file: File, chunkCount: Int = 4): String {
        val fileSize = file.length()
        if (fileSize == 0L) return ""

        val chunkSize = fileSize / chunkCount

        return (0 until chunkCount).toList().parallelStream()
            .map { i ->
                val start = i * chunkSize
                val end = if (i == chunkCount - 1) fileSize else (i + 1) * chunkSize
                searchChunkForTitle(file, start, end)
            }
            .filter { it.isNotEmpty() }
            .findFirst()
            .orElse("")
    }

    private fun searchChunkForTitle(file: File, start: Long, end: Long): String {
        try {
            file.inputStream().use { fis ->
                if (start > 0) fis.skip(start)
                val reader = fis.bufferedReader(Charsets.UTF_8)
                if (start > 0) reader.readLine() // skip partial line at boundary

                var bytesRead = start
                while (bytesRead < end) {
                    val line = reader.readLine() ?: break
                    bytesRead += line.toByteArray(Charsets.UTF_8).size + 1
                    if (line.contains("\"custom-title\"")) {
                        try {
                            val obj = JsonParser.parseString(line).asJsonObject
                            return obj.get("customTitle")?.asString ?: ""
                        } catch (_: Exception) {
                        }
                    }
                }
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
