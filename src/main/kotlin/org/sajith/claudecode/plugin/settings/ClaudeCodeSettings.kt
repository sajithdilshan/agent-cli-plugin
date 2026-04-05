package org.sajith.claudecode.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "ClaudeCodeSettings",
    storages = [Storage("claudeCodePlugin.xml")]
)
class ClaudeCodeSettings : PersistentStateComponent<ClaudeCodeSettings.State> {

    data class State(
        var claudeCommand: String = "cc",
        var terminalFontSize: Int = 13
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var claudeCommand: String
        get() = state.claudeCommand
        set(value) { state.claudeCommand = value }

    var terminalFontSize: Int
        get() = state.terminalFontSize
        set(value) { state.terminalFontSize = value }

    companion object {
        fun getInstance(): ClaudeCodeSettings =
            ApplicationManager.getApplication().getService(ClaudeCodeSettings::class.java)
    }
}
