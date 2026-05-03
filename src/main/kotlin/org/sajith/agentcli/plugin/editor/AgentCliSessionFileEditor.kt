package org.sajith.agentcli.plugin.editor

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.session.SessionManager
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import org.sajith.agentcli.plugin.terminal.EmbeddedAgentTerminal
import java.awt.BorderLayout
import java.awt.Font
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * FileEditor that hosts an agent CLI session inside an IntelliJ editor tab.
 *
 * Two modes:
 *  - Resume: `file.agentSessionId` is set; the editor runs the agent's resume command.
 *  - New session: `file.agentSessionId` is null; the editor runs the plain agent command
 *    so the user starts a fresh conversation in the editor tab. "Return to plugin view"
 *    is not offered for this mode because the agent-side id doesn't exist yet.
 */
class AgentCliSessionFileEditor(
    private val project: Project,
    private val file: AgentCliSessionVirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val disposable = Disposer.newDisposable("AgentCliSessionFileEditor")
    private val rootPanel = JPanel(BorderLayout())
    private val session: AgentCliSession
    private val terminal: EmbeddedAgentTerminal

    @Volatile
    private var returnToPluginRequested = false

    @Volatile
    private var disposed = false

    init {
        val sessionManager = SessionManager.getInstance(project)
        session =
            sessionManager.createSession(
                name = file.displayName,
                agentType = file.agentType,
                agentSessionId = file.agentSessionId,
                isEditorHosted = true,
            ).also { it.editorFileKey = file.key }

        val baseCmd = getCommand(file.agentType)
        val command =
            if (file.agentSessionId != null) {
                resumeCommandFor(file.agentType, baseCmd, file.agentSessionId)
            } else {
                baseCmd
            }
        val workingDir = project.basePath ?: System.getProperty("user.home")

        terminal =
            EmbeddedAgentTerminal(
                parentDisposable = disposable,
                project = project,
                session = session,
                workingDirectory = workingDir,
                command = command,
                isResume = file.agentSessionId != null,
                onExit = {
                    SwingUtilities.invokeLater {
                        if (!disposed) {
                            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(file)
                        }
                    }
                },
            )

        rootPanel.add(createBanner(), BorderLayout.NORTH)
        rootPanel.add(terminal.component, BorderLayout.CENTER)
        terminal.setResizeEnabled(true)

        project.messageBus.connect(disposable)
            .subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener { terminal.applyTheme() },
            )

        terminal.start()
    }

    private fun createBanner(): JComponent {
        val label =
            JLabel("Session opened in editor.").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, JBUI.Fonts.smallFont().size2D)
                border = JBUI.Borders.emptyRight(8)
            }

        val banner =
            JPanel(BorderLayout()).apply {
                background = JBColor.PanelBackground
                border = JBUI.Borders.empty(4, 8)
                add(label, BorderLayout.WEST)
            }

        // Return-to-plugin only works when we have an agent-side id to resume from.
        // For brand-new editor sessions there is no id yet, so the link is omitted.
        if (file.agentSessionId != null) {
            val link =
                ActionLink("Return to plugin view") {
                    returnToPluginRequested = true
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(file)
                }
            banner.add(link, BorderLayout.EAST)
        }

        return banner
    }

    private fun resumeCommandFor(
        agentType: AgentType,
        cmd: String,
        sessionId: String,
    ): String =
        when (agentType) {
            AgentType.CODEX -> "$cmd resume $sessionId"
            else -> "$cmd --resume $sessionId"
        }

    private fun getCommand(agentType: AgentType): String {
        val settings = AgentCliSettings.getInstance()
        return when (agentType) {
            AgentType.CLAUDE -> settings.claudeCommand
            AgentType.CURSOR -> settings.cursorCommand
            AgentType.GEMINI -> settings.geminiCommand
            AgentType.CODEX -> settings.codexCommand
        }
    }

    override fun getComponent(): JComponent = rootPanel

    override fun getPreferredFocusedComponent(): JComponent = terminal.component

    override fun getName(): String = file.displayName

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getFile() = file

    override fun dispose() {
        if (disposed) return
        disposed = true

        SessionManager.getInstance(project).removeSession(session)
        Disposer.dispose(disposable)

        val resumeId = file.agentSessionId
        if (returnToPluginRequested && resumeId != null) {
            // Post after this dispose completes so the tool window sees a clean state.
            val bridge = AgentCliEditorBridge.getInstance(project)
            ApplicationManager.getApplication().invokeLater {
                bridge.requestResumeInPluginView(file.agentType, resumeId, file.displayName)
            }
        }
    }

    companion object {
        @Suppress("unused")
        val EDITOR_KEY: Key<AgentCliSessionFileEditor> = Key.create("agent-cli-session-file-editor")
    }
}
