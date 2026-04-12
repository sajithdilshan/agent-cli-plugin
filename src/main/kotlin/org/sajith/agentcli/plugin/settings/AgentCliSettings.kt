package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import org.sajith.agentcli.plugin.AgentType

@Service(Service.Level.APP)
@State(
    name = "AgentCLISettings",
    storages = [Storage("AgentCLIPlugin.xml")],
)
class AgentCliSettings : PersistentStateComponent<AgentCliSettings.State> {
    data class State(
        var claudeEnabled: Boolean = true,
        var claudeCommand: String = "claude",
        var cursorEnabled: Boolean = true,
        var cursorCommand: String = "agent",
        var geminiEnabled: Boolean = true,
        var geminiCommand: String = "gemini",
        var codexEnabled: Boolean = true,
        var codexCommand: String = "codex",
        var terminalFontSize: Int = 13,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var claudeEnabled: Boolean
        get() = state.claudeEnabled
        set(value) {
            state.claudeEnabled = value
        }

    var claudeCommand: String
        get() = state.claudeCommand
        set(value) {
            state.claudeCommand = value
        }

    var cursorEnabled: Boolean
        get() = state.cursorEnabled
        set(value) {
            state.cursorEnabled = value
        }

    var cursorCommand: String
        get() = state.cursorCommand
        set(value) {
            state.cursorCommand = value
        }

    var geminiEnabled: Boolean
        get() = state.geminiEnabled
        set(value) {
            state.geminiEnabled = value
        }

    var geminiCommand: String
        get() = state.geminiCommand
        set(value) {
            state.geminiCommand = value
        }

    var codexEnabled: Boolean
        get() = state.codexEnabled
        set(value) {
            state.codexEnabled = value
        }

    var codexCommand: String
        get() = state.codexCommand
        set(value) {
            state.codexCommand = value
        }

    fun isAgentEnabled(agentType: AgentType): Boolean =
        when (agentType) {
            AgentType.CLAUDE -> claudeEnabled
            AgentType.CURSOR -> cursorEnabled
            AgentType.GEMINI -> geminiEnabled
            AgentType.CODEX -> codexEnabled
        }

    val enabledAgentTypes: List<AgentType>
        get() = AgentType.entries.filter { isAgentEnabled(it) }

    var terminalFontSize: Int
        get() = state.terminalFontSize
        set(value) {
            state.terminalFontSize = value
        }

    fun interface SettingsChangeListener {
        fun settingsChanged()
    }

    companion object {
        val SETTINGS_CHANGED_TOPIC = Topic.create("AgentCLI.SettingsChanged", SettingsChangeListener::class.java)

        fun getInstance(): AgentCliSettings = ApplicationManager.getApplication().getService(AgentCliSettings::class.java)
    }
}
