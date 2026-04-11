package org.sajith.agentcli.plugin.session

import com.intellij.openapi.diagnostic.Logger
import org.sajith.agentcli.plugin.AgentType
import java.io.File

object SessionHistoryDeleter {
    private val LOG = Logger.getInstance(SessionHistoryDeleter::class.java)

    fun deleteSession(
        sessionId: String,
        agentType: AgentType,
        projectPath: String,
    ): Boolean {
        return try {
            when (agentType) {
                AgentType.CLAUDE -> deleteClaudeSession(sessionId, projectPath)
                AgentType.CURSOR -> deleteCursorSession(sessionId, projectPath)
                AgentType.GEMINI -> deleteGeminiSession(sessionId, projectPath)
            }
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] Failed to delete $agentType session $sessionId", e)
            false
        }
    }

    private fun deleteClaudeSession(
        sessionId: String,
        projectPath: String,
    ): Boolean {
        val claudeDir = File(System.getProperty("user.home"), ".claude/projects")
        if (!claudeDir.exists()) return false

        val encodedPath = SessionPathResolver.encodeClaudeProjectPath(projectPath)
        val projectDir = SessionPathResolver.resolveProjectDirectory(claudeDir, encodedPath) ?: return false

        val sessionFile = projectDir.resolve("$sessionId.jsonl")
        if (!sessionFile.exists()) return false

        val deleted = sessionFile.delete()
        LOG.info("[AgentCLI] Deleted Claude session file: ${sessionFile.absolutePath} — $deleted")
        return deleted
    }

    private fun deleteCursorSession(
        sessionId: String,
        projectPath: String,
    ): Boolean {
        val cursorDir = File(System.getProperty("user.home"), ".cursor/projects")
        if (!cursorDir.exists()) return false

        val encodedPath = SessionPathResolver.encodeCursorProjectPath(projectPath)
        val projectDir = SessionPathResolver.resolveProjectDirectory(cursorDir, encodedPath) ?: return false

        val sessionDir = projectDir.resolve("agent-transcripts/$sessionId")
        if (!sessionDir.exists() || !sessionDir.isDirectory) return false

        val deleted = sessionDir.deleteRecursively()
        LOG.info("[AgentCLI] Deleted Cursor session directory: ${sessionDir.absolutePath} — $deleted")
        return deleted
    }

    private fun deleteGeminiSession(
        sessionId: String,
        projectPath: String,
    ): Boolean {
        val geminiDir = File(System.getProperty("user.home"), ".gemini")
        if (!geminiDir.exists()) return false

        val projectName = SessionPathResolver.resolveGeminiProjectName(geminiDir, projectPath) ?: return false
        val chatsDir = geminiDir.resolve("tmp/$projectName/chats")
        if (!chatsDir.exists()) return false

        val sessionFile = chatsDir.resolve("$sessionId.json")
        if (!sessionFile.exists()) return false

        val deleted = sessionFile.delete()
        LOG.info("[AgentCLI] Deleted Gemini session file: ${sessionFile.absolutePath} — $deleted")
        return deleted
    }
}
