package org.sajith.agentcli.plugin.session

import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.settings.AgentCliSettings

/**
 * Builds the shell command used to launch or resume an agent session.
 *
 * For the SANDBOX agent the command template may contain a `{dir}` placeholder
 * which is replaced with the (quoted) project path; if absent the path is
 * appended. Resume syntax follows the underlying agent (Codex uses
 * `resume <id>`, everything else `--resume <id>`).
 */
object AgentCommandBuilder {
    private const val DIR_PLACEHOLDER = "{dir}"

    fun newSessionCommand(
        agentType: AgentType,
        projectPath: String,
    ): String {
        val settings = AgentCliSettings.getInstance()
        return when (agentType) {
            AgentType.SANDBOX -> substituteDir(settings.sandboxCommand, projectPath)
            else -> baseCommand(agentType)
        }
    }

    fun resumeCommand(
        agentType: AgentType,
        sessionId: String,
        projectPath: String,
    ): String {
        if (agentType == AgentType.SANDBOX) {
            val settings = AgentCliSettings.getInstance()
            val base = substituteDir(settings.sandboxCommand, projectPath)
            return appendResume(base, settings.sandboxUnderlyingAgent, sessionId)
        }
        return appendResume(baseCommand(agentType), agentType, sessionId)
    }

    private fun appendResume(
        base: String,
        underlying: AgentType,
        sessionId: String,
    ): String =
        when (underlying) {
            AgentType.CODEX -> "$base resume $sessionId"
            else -> "$base --resume $sessionId"
        }

    private fun substituteDir(
        template: String,
        projectPath: String,
    ): String {
        val quoted = quote(projectPath)
        return if (template.contains(DIR_PLACEHOLDER)) {
            template.replace(DIR_PLACEHOLDER, quoted)
        } else {
            "$template $quoted"
        }
    }

    private fun quote(path: String): String = "\"" + path.replace("\"", "\\\"") + "\""

    private fun baseCommand(agentType: AgentType): String {
        val settings = AgentCliSettings.getInstance()
        return when (agentType) {
            AgentType.CLAUDE -> settings.claudeCommand
            AgentType.CURSOR -> settings.cursorCommand
            AgentType.GEMINI -> settings.geminiCommand
            AgentType.CODEX -> settings.codexCommand
            AgentType.SANDBOX -> settings.sandboxCommand
        }
    }
}
