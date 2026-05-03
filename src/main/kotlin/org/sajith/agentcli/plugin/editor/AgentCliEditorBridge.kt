package org.sajith.agentcli.plugin.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.editor.AgentCliEditorBridge.Companion.RESUME_IN_PLUGIN_TOPIC

/**
 * Routes "open in editor" and "return to plugin view" requests between the tool-window
 * sidebar/panel and the editor-hosted session.
 *
 * The tool-window panel subscribes to [RESUME_IN_PLUGIN_TOPIC] and reopens the session
 * in the sidebar when the editor asks for it.
 */
@Service(Service.Level.PROJECT)
class AgentCliEditorBridge(private val project: Project) {
    fun openInEditor(
        agentType: AgentType,
        agentSessionId: String,
        displayName: String,
    ) {
        val file = AgentCliSessionVirtualFile(agentType, agentSessionId, displayName)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun requestResumeInPluginView(
        agentType: AgentType,
        agentSessionId: String,
        displayName: String,
    ) {
        project.messageBus.syncPublisher(RESUME_IN_PLUGIN_TOPIC)
            .resumeInPluginView(agentType, agentSessionId, displayName)
    }

    fun interface ResumeInPluginListener {
        fun resumeInPluginView(
            agentType: AgentType,
            agentSessionId: String,
            displayName: String,
        )
    }

    companion object {
        val RESUME_IN_PLUGIN_TOPIC: Topic<ResumeInPluginListener> =
            Topic.create("AgentCLI.ResumeInPlugin", ResumeInPluginListener::class.java)

        fun getInstance(project: Project): AgentCliEditorBridge =
            project.getService(AgentCliEditorBridge::class.java)
    }
}
