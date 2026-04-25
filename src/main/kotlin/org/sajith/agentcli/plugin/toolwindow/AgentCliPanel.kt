package org.sajith.agentcli.plugin.toolwindow

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.OnePixelSplitter
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.session.SessionHistoryDeleter
import org.sajith.agentcli.plugin.session.SessionManager
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import org.sajith.agentcli.plugin.terminal.CefTerminalPanel
import org.sajith.agentcli.plugin.terminal.PtyBridge
import org.sajith.agentcli.plugin.terminal.TerminalFlowController
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
    private val terminalCardLayout = CardLayout()
    private val terminalPanel = JPanel(terminalCardLayout)
    private val terminalPanels = mutableMapOf<String, CefTerminalPanel>()
    private val ptyBridges = mutableMapOf<String, PtyBridge>()
    private var activeSessionId: String? = null

    private val sidebar =
        SessionSidebarPanel(
            project = project,
            onNewSession = { agentType -> createNewSession(agentType) },
            onSessionSelected = { session -> switchToSession(session) },
            onSessionClosed = { session -> closeSession(session) },
            onSessionDeleted = { session -> deleteSession(session) },
            onResumeSession = { agentType, sessionId, title -> resumeSession(agentType, sessionId, title) },
            onHistorySessionDeleted = { historicalSession -> deleteHistorySession(historicalSession) },
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

        // Keep embedded terminals in sync when the IDE LaF / editor colors change.
        project.messageBus.connect(parentDisposable)
            .subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener {
                    terminalPanels.values.forEach { it.applyTheme() }
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
    }

    fun createNewSession(agentType: AgentType = AgentType.CLAUDE) {
        val cmd = getCommand(agentType)
        val session = sessionManager.createSession(agentType = agentType)
        createTerminalForSession(session, cmd)
    }

    private fun resumeSession(
        agentType: AgentType,
        sessionId: String,
        title: String?,
    ) {
        val cmd = getCommand(agentType)
        val session = sessionManager.createSession(title, agentType = agentType, agentSessionId = sessionId)
        val resumeCmd =
            when (agentType) {
                AgentType.CODEX -> "$cmd resume $sessionId"
                else -> "$cmd --resume $sessionId"
            }
        createTerminalForSession(session, resumeCmd, isResume = true)
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

        lateinit var ptyBridge: PtyBridge
        lateinit var flowController: TerminalFlowController

        val cefPanel =
            CefTerminalPanel(
                parentDisposable = parentDisposable,
                onInput = { data -> ptyBridge.write(data) },
                onResize = { cols, rows -> ptyBridge.resize(cols, rows) },
                onAck = { flowController.ack() },
                loadingText = if (isResume) "Resuming Session..." else "Starting Session...",
            )

        val flowControlEnabled = AgentCliSettings.getInstance().flowControlEnabled
        flowController =
            TerminalFlowController(
                highWatermark = 8,
                lowWatermark = 3,
                callbackByteLimit = 200_000,
                onWrite = { data, needsAck ->
                    if (needsAck && flowControlEnabled) {
                        cefPanel.writeToTerminalAck(data)
                    } else {
                        cefPanel.writeToTerminal(data)
                    }
                },
                onPause = { if (flowControlEnabled) ptyBridge.pause() },
                onResume = { if (flowControlEnabled) ptyBridge.resume() },
            )

        val shellCommand = shellCommandFor(command)

        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        // Ensure the PTY locale is UTF-8 so programs interpret I/O correctly.
        // If the inherited LANG/LC_CTYPE is missing or set to "C"/"POSIX",
        // fall back to en_US.UTF-8 to avoid ASCII-only mode.
        val lang = env["LANG"].orEmpty()
        val lcAll = env["LC_ALL"].orEmpty()
        val lcCtype = env["LC_CTYPE"].orEmpty()
        val hasUtf8Locale =
            listOf(lang, lcAll, lcCtype).any {
                it.contains("UTF-8", ignoreCase = true) || it.contains("utf8", ignoreCase = true)
            }
        if (!hasUtf8Locale) {
            env["LANG"] = "en_US.UTF-8"
        }

        ptyBridge =
            PtyBridge(
                command = shellCommand,
                workingDirectory = workingDir,
                environment = env,
                onOutput = { data -> flowController.write(data) },
                onExit = { exitCode ->
                    LOG.info("[AgentCLI] Agent process exited with code $exitCode for session ${session.id}")
                    SwingUtilities.invokeLater { closeSession(session) }
                },
            )
        Disposer.register(parentDisposable, ptyBridge)

        terminalPanels[session.id] = cefPanel
        ptyBridges[session.id] = ptyBridge
        terminalPanel.add(cefPanel.component, session.id)
        sidebar.addSession(session)

        // Disable resize on the previously active terminal, enable on the new one
        activeSessionId?.let { terminalPanels[it]?.setResizeEnabled(false) }
        activeSessionId = session.id
        cefPanel.setResizeEnabled(true)

        terminalCardLayout.show(terminalPanel, session.id)
        updateToolWindowTitle()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ptyBridge.start()
            } catch (e: Exception) {
                LOG.error("[AgentCLI] Failed to start PTY for session ${session.id}", e)
            }
        }
    }

    private fun shellCommandFor(agentCommand: String): Array<String> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return if (isWindows) {
            val shell = System.getenv("COMSPEC") ?: "cmd.exe"
            arrayOf(shell, "/c", agentCommand)
        } else {
            val shell = System.getenv("SHELL") ?: "/bin/sh"
            arrayOf(shell, "-l", "-i", "-c", "exec $agentCommand")
        }
    }

    private fun switchToSession(session: AgentCliSession) {
        if (terminalPanels.containsKey(session.id)) {
            // Disable resize on old active terminal, enable on new one
            activeSessionId?.let { terminalPanels[it]?.setResizeEnabled(false) }
            activeSessionId = session.id
            terminalPanels[session.id]?.setResizeEnabled(true)

            terminalCardLayout.show(terminalPanel, session.id)
            sidebar.selectSession(session)
            terminalPanels[session.id]?.focus()
            updateToolWindowTitle()
        } else {
            LOG.warn("[AgentCLI] switchToSession: no terminal panel found for session ${session.id}")
        }
    }

    private fun closeSession(session: AgentCliSession) {
        val cefPanel = terminalPanels.remove(session.id)
        val ptyBridge = ptyBridges.remove(session.id)
        val closedActiveSession = session.id == activeSessionId

        if (cefPanel != null) {
            // Stop debounced resize → fitAndRestore on this panel before dispose. Cannot use
            // terminalPanels[activeSessionId] here — the closing session was already removed from the map.
            cefPanel.setResizeEnabled(false)

            // CardLayout: show a different card before remove() so removing a card does not reshuffle the visible panel.
            if (closedActiveSession) {
                val remainingId = terminalPanels.keys.firstOrNull()
                if (remainingId != null) {
                    activeSessionId = remainingId
                    terminalPanels[remainingId]?.setResizeEnabled(true)
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

            cefPanel.component.isVisible = false

            // Remove from hierarchy while this card is not shown, then dispose after detach.
            terminalPanel.remove(cefPanel.component)
            terminalPanel.revalidate()
            terminalPanel.repaint()

            Disposer.dispose(cefPanel)
        }
        if (ptyBridge != null) {
            Disposer.dispose(ptyBridge)
        }

        sessionManager.removeSession(session)
        sidebar.removeSession(session)

        // removeSession() selects the last list row; align selection with the visible terminal card.
        activeSessionId?.let { id ->
            sessionManager.sessions.find { it.id == id }?.let { sidebar.selectSession(it) }
        }
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

    private fun deleteHistorySession(historicalSession: org.sajith.agentcli.plugin.session.HistoricalSession) {
        val projectPath = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            SessionHistoryDeleter.deleteSession(
                historicalSession.sessionId,
                historicalSession.agentType,
                projectPath,
            )
        }
    }

    private fun updateToolWindowTitle() {
        val session = activeSessionId?.let { id -> sessionManager.sessions.find { it.id == id } }
        toolWindow.stripeTitle = if (session != null) "Agent CLI - ${session.displayName}" else "Agent CLI"
    }

    companion object {
        private val LOG = Logger.getInstance(AgentCliPanel::class.java)
    }
}
