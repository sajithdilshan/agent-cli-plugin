package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Project-scoped settings. Stored in the project's workspace file (machine-local,
 * not shared via VCS by default) so per-project values like sandbox arguments
 * don't leak into the application-level config.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "AgentCLIProjectSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class AgentCliProjectSettings : PersistentStateComponent<AgentCliProjectSettings.State> {
    data class State(
        var sandboxProjectArgs: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** Extra arguments substituted into the sandbox command's `{project_args}` placeholder. */
    var sandboxProjectArgs: String
        get() = state.sandboxProjectArgs
        set(value) {
            state.sandboxProjectArgs = value
        }

    companion object {
        fun getInstance(project: Project): AgentCliProjectSettings = project.getService(AgentCliProjectSettings::class.java)
    }
}
