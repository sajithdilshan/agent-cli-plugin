package org.sajith.agentcli.plugin.session

import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import java.io.File

/**
 * Reads history for the generic SANDBOX agent by delegating to the underlying agent's
 * reader, but rooted at the configured sandbox history dir instead of the agent's
 * default home (e.g. `~/.claude-crate` in place of `~/.claude`).
 */
object SandboxHistoryReader {
    fun readHistory(projectPath: String): List<HistoricalSession> {
        val settings = AgentCliSettings.getInstance()
        val home: File = SessionPathResolver.expandHome(settings.sandboxHistoryDir)
        return when (settings.sandboxUnderlyingAgent) {
            AgentType.CLAUDE -> ClaudeCodeHistoryReader.readHistory(projectPath, home)
            AgentType.CURSOR -> CursorHistoryReader.readHistory(projectPath, home)
            AgentType.CODEX -> CodexHistoryReader.readHistory(projectPath, home)
            AgentType.SANDBOX -> emptyList()
        }
    }
}
