package org.sajith.agentcli.plugin.toolwindow

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.OnePixelSplitter
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.editor.AgentCliEditorBridge
import org.sajith.agentcli.plugin.editor.AgentCliSessionVirtualFile
import org.sajith.agentcli.plugin.notify.SessionAttentionService
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.session.HistoricalSession
import org.sajith.agentcli.plugin.session.SessionHistoryDeleter
import org.sajith.agentcli.plugin.session.SessionManager
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import org.sajith.agentcli.plugin.terminal.EmbeddedAgentTerminal
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class AgentCliPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()) {
    private val sessionManager = SessionManager.getInstance(project)
    private val attentionService = SessionAttentionService.getInstance(project)
    private val terminalCardLayout = CardLayout()
    private val terminalPanel = JPanel(terminalCardLayout)
    private val terminals = mutableMapOf<String, EmbeddedAgentTerminal>()
    private var activeSessionId: String? = null
    private var selectedEditorHosted = false

    private val sidebar =
        SessionSidebarPanel(
            project = project,
            onNewSession = { agentType -> createNewSession(agentType) },
            onSessionSelected = { session -> switchToSession(session) },
            onSessionClosed = { session -> closeSession(session) },
            onSessionDeleted = { session -> deleteSession(session) },
            onResumeSession = { agentType, sessionId, title -> resumeSession(agentType, sessionId, title) },
            onHistorySessionDeleted = { historicalSession -> deleteHistorySession(historicalSession) },
            onOpenSessionInEditor = { session -> openSessionInEditor(session) },
            onOpenHistorySessionInEditor = { historicalSession -> openHistorySessionInEditor(historicalSession) },
        )

    private val splitter = OnePixelSplitter(false, 0.2f)
    private var savedProportion = 0.2f

    init {
        Disposer.register(parentDisposable, sidebar)
        splitter.firstComponent = sidebar
        splitter.secondComponent = terminalPanel
        sidebar.onCollapseToggle = { collapsed ->
            if (collapsed) {
                savedProportion = splitter.proportion
                splitter.proportion = 0.0f
            } else {
                splitter.proportion = savedProportion
            }
        }
        add(splitter, BorderLayout.CENTER)
        updateTerminalPanelVisibility()

        // Keep embedded terminals in sync when the IDE LaF / editor colors change.
        project.messageBus.connect(parentDisposable)
            .subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener {
                    terminals.values.forEach { it.applyTheme() }
                },
            )

        // Reload history when agent settings change (e.g. agents enabled/disabled).
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(
                AgentCliSettings.SETTINGS_CHANGED_TOPIC,
                AgentCliSettings.SettingsChangeListener {
                    sidebar.loadHistory()
                },
            )

        // When the editor-hosted session asks to return to the plugin view, resume here.
        project.messageBus.connect(parentDisposable)
            .subscribe(
                AgentCliEditorBridge.RESUME_IN_PLUGIN_TOPIC,
                AgentCliEditorBridge.ResumeInPluginListener { agentType, sessionId, displayName ->
                    toolWindow.activate(null)
                    resumeSession(agentType, sessionId, displayName)
                },
            )

        // Refresh the history list whenever an editor-hosted session tab is closed so
        // the session reappears under Today / Yesterday / etc.
        project.messageBus.connect(parentDisposable)
            .subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileClosed(
                        source: FileEditorManager,
                        file: VirtualFile,
                    ) {
                        if (file is AgentCliSessionVirtualFile) {
                            sidebar.loadHistory()
                        }
                    }

                    override fun selectionChanged(event: FileEditorManagerEvent) {}
                },
            )

        // Mirror editor-hosted sessions in the sidebar's active list so users can see
        // and switch back to them. Terminal-hosted sessions are already managed inline
        // by createTerminalForSession / closeSession, but we also ignore duplicate
        // adds defensively via sidebar contains-check.
        project.messageBus.connect(parentDisposable)
            .subscribe(
                SessionManager.SESSION_LIFECYCLE_TOPIC,
                object : SessionManager.SessionLifecycleListener {
                    override fun sessionAdded(session: AgentCliSession) {
                        if (session.isEditorHosted) {
                            // Defer: sessionAdded fires from inside FileEditorManager.openFile's
                            // coroutine on EDT; mutating the sidebar synchronously here can
                            // re-enter openFile via the list selection listener and deadlock.
                            SwingUtilities.invokeLater { sidebar.addSession(session) }
                        }
                    }

                    override fun sessionRemoved(session: AgentCliSession) {
                        if (session.isEditorHosted) {
                            SwingUtilities.invokeLater { sidebar.removeSession(session) }
                        }
                    }
                },
            )
    }

    private fun openSessionInEditor(session: AgentCliSession) {
        val agentSessionId = session.agentSessionId ?: return
        val agentType = session.agentType
        val displayName = session.displayName
        closeSession(session)
        AgentCliEditorBridge.getInstance(project).openInEditor(agentType, agentSessionId, displayName)
    }

    private fun openHistorySessionInEditor(historicalSession: HistoricalSession) {
        AgentCliEditorBridge.getInstance(project).openInEditor(
            historicalSession.agentType,
            historicalSession.sessionId,
            historicalSession.displayName,
        )
        // The editor's createSession fires SESSION_LIFECYCLE_TOPIC which adds the row
        // to the sidebar and reloads history; no manual refresh needed here.
    }

    fun createNewSession(agentType: AgentType = AgentType.CLAUDE) {
        if (AgentCliSettings.getInstance().alwaysOpenNewSessionInEditor) {
            val displayName = defaultNewSessionName()
            AgentCliEditorBridge.getInstance(project).openNewSessionInEditor(agentType, displayName)
            return
        }
        val cmd = getCommand(agentType)
        val session = sessionManager.createSession(agentType = agentType)
        createTerminalForSession(session, cmd)
    }

    private fun defaultNewSessionName(): String {
        val existing = sessionManager.sessions.size
        return "Session ${existing + 1}"
    }

    private fun resumeSession(
        agentType: AgentType,
        sessionId: String,
        title: String?,
    ) {
        val cmd = getCommand(agentType)
        val session = sessionManager.createSession(title, agentType = agentType, agentSessionId = sessionId)
        val resumeCmd = resumeCommandFor(agentType, cmd, sessionId)
        createTerminalForSession(session, resumeCmd, isResume = true)
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

    private fun createTerminalForSession(
        session: AgentCliSession,
        command: String,
        isResume: Boolean = false,
    ) {
        val workingDir = project.basePath ?: System.getProperty("user.home")

        val terminal =
            EmbeddedAgentTerminal(
                parentDisposable = parentDisposable,
                project = project,
                session = session,
                workingDirectory = workingDir,
                command = command,
                isResume = isResume,
                onExit = { closeSession(session) },
            )

        terminals[session.id] = terminal
        terminalPanel.add(terminal.component, session.id)
        sidebar.addSession(session)

        // Disable resize on the previously active terminal, enable on the new one
        activeSessionId?.let { terminals[it]?.setResizeEnabled(false) }
        activeSessionId = session.id
        terminal.setResizeEnabled(true)

        terminalCardLayout.show(terminalPanel, session.id)
        selectedEditorHosted = false
        updateTerminalPanelVisibility()
        updateToolWindowTitle()

        terminal.start()
    }

    /**
     * Look up the virtual file that an editor-hosted session is already open in.
     *
     * For resumed sessions the key == agentSessionId so we could rebuild the file, but
     * for brand-new editor sessions the key is a random UUID stored on the session.
     * Either way, asking FileEditorManager for its open files is simpler and avoids
     * re-deriving keys.
     */
    private fun virtualFileFor(session: AgentCliSession): AgentCliSessionVirtualFile? {
        val key = session.editorFileKey ?: session.agentSessionId ?: return null
        return FileEditorManager.getInstance(project).openFiles
            .asSequence()
            .filterIsInstance<AgentCliSessionVirtualFile>()
            .firstOrNull { it.key == key }
    }

    private fun switchToSession(session: AgentCliSession) {
        if (session.isEditorHosted) {
            val file = virtualFileFor(session) ?: return
            selectedEditorHosted = true
            updateTerminalPanelVisibility()
            FileEditorManager.getInstance(project).openFile(file, true)
            return
        }
        val terminal = terminals[session.id]
        if (terminal != null) {
            // Disable resize on old active terminal, enable on new one
            activeSessionId?.let { terminals[it]?.setResizeEnabled(false) }
            activeSessionId = session.id
            terminal.setResizeEnabled(true)

            terminalCardLayout.show(terminalPanel, session.id)
            sidebar.selectSession(session)
            terminal.focus()
            attentionService.clearByPluginSessionId(session.id)
            selectedEditorHosted = false
            updateTerminalPanelVisibility()
            updateToolWindowTitle()
        } else {
            LOG.warn("[AgentCLI] switchToSession: no terminal found for session ${session.id}")
        }
    }

    private fun closeSession(session: AgentCliSession) {
        if (session.isEditorHosted) {
            val file = virtualFileFor(session) ?: return
            // Closing the editor triggers AgentCliSessionFileEditor.dispose(), which
            // calls SessionManager.removeSession and fires SESSION_LIFECYCLE_TOPIC,
            // which removes the row from the sidebar's active list.
            FileEditorManager.getInstance(project).closeFile(file)
            return
        }
        val terminal = terminals.remove(session.id)
        val closedActiveSession = session.id == activeSessionId

        if (terminal != null) {
            // Stop debounced resize → fitAndRestore on this panel before dispose. Cannot use
            // terminals[activeSessionId] here — the closing session was already removed from the map.
            terminal.setResizeEnabled(false)

            // CardLayout: show a different card before remove() so removing a card does not reshuffle the visible panel.
            if (closedActiveSession) {
                val remainingId = terminals.keys.firstOrNull()
                if (remainingId != null) {
                    activeSessionId = remainingId
                    terminals[remainingId]?.setResizeEnabled(true)
                    terminalCardLayout.show(terminalPanel, remainingId)
                } else {
                    activeSessionId = null
                }
            } else {
                // Closing a background tab: keep the current session visible and resize handling unchanged.
                activeSessionId?.let { id ->
                    terminalCardLayout.show(terminalPanel, id)
                }
            }

            terminal.component.isVisible = false

            // Remove from hierarchy while this card is not shown, then dispose after detach.
            terminalPanel.remove(terminal.component)
            terminalPanel.revalidate()
            terminalPanel.repaint()

            Disposer.dispose(terminal.cefPanel)
        }

        sessionManager.removeSession(session)
        sidebar.removeSession(session)

        // removeSession() selects the last list row; align selection with the visible terminal card.
        activeSessionId?.let { id ->
            sessionManager.sessions.find { it.id == id }?.let { sidebar.selectSession(it) }
        }
        if (activeSessionId == null) {
            selectedEditorHosted = false
        }
        updateTerminalPanelVisibility()
        updateToolWindowTitle()
    }

    private fun deleteSession(session: AgentCliSession) {
        val projectPath = project.basePath ?: return
        closeSession(session)
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessionId = session.agentSessionId ?: session.id
            SessionHistoryDeleter.deleteSession(sessionId, session.agentType, projectPath)
        }
    }

    private fun deleteHistorySession(historicalSession: HistoricalSession) {
        val projectPath = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            SessionHistoryDeleter.deleteSession(
                historicalSession.sessionId,
                historicalSession.agentType,
                projectPath,
            )
        }
    }

    /**
     * Hide the terminal panel when no terminal session is active (either because the
     * selected session lives in an editor tab, or because there are no sessions at all).
     * Keeps the sidebar from looking like a dead black rectangle in those cases.
     */
    private fun updateTerminalPanelVisibility() {
        val hasTerminal = activeSessionId != null && !selectedEditorHosted
        sidebar.setCollapseButtonVisible(hasTerminal)
        if (terminalPanel.isVisible == hasTerminal) return
        terminalPanel.isVisible = hasTerminal
        splitter.revalidate()
        splitter.repaint()
    }

    private fun updateToolWindowTitle() {
        val session = activeSessionId?.let { id -> sessionManager.sessions.find { it.id == id } }
        toolWindow.stripeTitle = if (session != null) "Agent CLI - ${session.displayName}" else "Agent CLI"
    }

    companion object {
        private val LOG = Logger.getInstance(AgentCliPanel::class.java)
    }
}
