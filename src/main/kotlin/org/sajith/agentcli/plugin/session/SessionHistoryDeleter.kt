package org.sajith.agentcli.plugin.session

import com.intellij.openapi.diagnostic.Logger
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import java.io.File

object SessionHistoryDeleter {
    private val LOG = Logger.getInstance(SessionHistoryDeleter::class.java)

    fun deleteSession(
        sessionId: String,
        agentType: AgentType,
        projectPath: String,
    ): Boolean {
        return try {
            deleteFor(agentType, sessionId, projectPath, agentHome = null)
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] Failed to delete $agentType session $sessionId", e)
            false
        }
    }

    private fun deleteFor(
        agentType: AgentType,
        sessionId: String,
        projectPath: String,
        agentHome: File?,
    ): Boolean =
        when (agentType) {
            AgentType.CLAUDE -> deleteClaudeSession(sessionId, projectPath, agentHome)
            AgentType.CURSOR -> deleteCursorSession(sessionId, projectPath, agentHome)
            AgentType.CODEX -> deleteCodexSession(sessionId, agentHome)
            AgentType.SANDBOX -> deleteSandboxSession(sessionId, projectPath)
        }

    private fun deleteSandboxSession(
        sessionId: String,
        projectPath: String,
    ): Boolean {
        val settings = AgentCliSettings.getInstance()
        val home = SessionPathResolver.expandHome(settings.sandboxHistoryDir)
        return deleteFor(settings.sandboxUnderlyingAgent, sessionId, projectPath, home)
    }

    private fun deleteClaudeSession(
        sessionId: String,
        projectPath: String,
        agentHome: File?,
    ): Boolean {
        val claudeDir = (agentHome ?: File(System.getProperty("user.home"), ".claude")).resolve("projects")
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
        agentHome: File?,
    ): Boolean {
        val cursorDir = (agentHome ?: File(System.getProperty("user.home"), ".cursor")).resolve("projects")
        if (!cursorDir.exists()) return false

        val encodedPath = SessionPathResolver.encodeCursorProjectPath(projectPath)
        val projectDir = SessionPathResolver.resolveProjectDirectory(cursorDir, encodedPath) ?: return false

        val sessionDir = projectDir.resolve("agent-transcripts/$sessionId")
        if (!sessionDir.exists() || !sessionDir.isDirectory) return false

        val deleted = sessionDir.deleteRecursively()
        LOG.info("[AgentCLI] Deleted Cursor session directory: ${sessionDir.absolutePath} — $deleted")
        return deleted
    }

    private fun deleteCodexSession(
        sessionId: String,
        agentHome: File?,
    ): Boolean {
        val codexDir = agentHome ?: File(System.getProperty("user.home"), ".codex")
        val sessionFile = CodexHistoryReader.findSessionFile(sessionId, codexDir)
        var fileDeleted = false
        if (sessionFile != null && sessionFile.exists()) {
            fileDeleted = sessionFile.delete()
            LOG.info("[AgentCLI] Deleted Codex session file: ${sessionFile.absolutePath} — $fileDeleted")
        }
        val indexUpdated = CodexHistoryReader.removeFromIndex(sessionId, codexDir)
        LOG.info("[AgentCLI] Removed Codex session from index: $sessionId — $indexUpdated")
        return fileDeleted || indexUpdated
    }
}
