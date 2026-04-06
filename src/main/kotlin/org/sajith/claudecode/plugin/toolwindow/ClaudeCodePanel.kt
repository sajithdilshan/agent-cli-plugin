package org.sajith.claudecode.plugin.toolwindow

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.sajith.claudecode.plugin.session.ClaudeCodeSession
import org.sajith.claudecode.plugin.session.SessionManager
import org.sajith.claudecode.plugin.settings.ClaudeCodeSettings
import org.sajith.claudecode.plugin.terminal.CefTerminalPanel
import org.sajith.claudecode.plugin.terminal.PtyBridge
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

class ClaudeCodePanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()) {

    private val sessionManager = SessionManager.getInstance(project)
    private val terminalCardLayout = CardLayout()
    private val terminalPanel = JPanel(terminalCardLayout)
    private val terminalPanels = mutableMapOf<String, CefTerminalPanel>()
    private val ptyBridges = mutableMapOf<String, PtyBridge>()
    private var activeSessionId: String? = null

    private val sidebar = SessionSidebarPanel(
        project = project,
        onNewSession = { createNewSession() },
        onSessionSelected = { session -> switchToSession(session) },
        onSessionClosed = { session -> closeSession(session) },
        onResumeSession = { sessionId, title -> resumeSession(sessionId, title) }
    )

    init {
        add(terminalPanel, BorderLayout.CENTER)
        add(sidebar, BorderLayout.WEST)

        // Keep embedded terminals in sync when the IDE LaF / editor colors change.
        project.messageBus.connect(parentDisposable)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener {
                terminalPanels.values.forEach { it.applyTheme() }
            })
    }

    fun createNewSession() {
        val cmd = ClaudeCodeSettings.getInstance().claudeCommand
        val session = sessionManager.createSession()
        createTerminalForSession(session, cmd)
    }

    private fun resumeSession(sessionId: String, title: String?) {
        val cmd = ClaudeCodeSettings.getInstance().claudeCommand
        val session = sessionManager.createSession(title, claudeSessionId = sessionId)
        createTerminalForSession(session, "$cmd --resume $sessionId")
    }

    private fun createTerminalForSession(session: ClaudeCodeSession, command: String) {
        val workingDir = project.basePath ?: System.getProperty("user.home")

        lateinit var ptyBridge: PtyBridge

        val cefPanel = CefTerminalPanel(
            parentDisposable = parentDisposable,
            onInput = { data -> ptyBridge.write(data) },
            onResize = { cols, rows -> ptyBridge.resize(cols, rows) }
        )

        val shellCommand = shellInvocation()

        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        ptyBridge = PtyBridge(
            command = shellCommand,
            workingDirectory = workingDir,
            environment = env,
            onOutput = { data -> cefPanel.writeToTerminal(data) },
            onExit = { }
        )

        terminalPanels[session.id] = cefPanel
        ptyBridges[session.id] = ptyBridge
        terminalPanel.add(cefPanel.component, session.id)
        sidebar.addSession(session)

        // Disable resize on the previously active terminal, enable on the new one
        activeSessionId?.let { terminalPanels[it]?.setResizeEnabled(false) }
        activeSessionId = session.id
        cefPanel.setResizeEnabled(true)

        terminalCardLayout.show(terminalPanel, session.id)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ptyBridge.start()
                Thread.sleep(500)
                ptyBridge.write("$command\n")
            } catch (e: Exception) {
                LOG.error("[ClaudeCode] Failed to start PTY for session ${session.id}", e)
            }
        }
    }

    private fun shellInvocation(): Array<String> {
        val shell = System.getenv("SHELL") ?: System.getenv("COMSPEC") ?: "/bin/sh"
        return if (System.getProperty("os.name").lowercase().contains("win")) {
            arrayOf(shell)
        } else {
            arrayOf(shell, "-l")
        }
    }

    private fun switchToSession(session: ClaudeCodeSession) {
        if (terminalPanels.containsKey(session.id)) {
            // Disable resize on old active terminal, enable on new one
            activeSessionId?.let { terminalPanels[it]?.setResizeEnabled(false) }
            activeSessionId = session.id
            terminalPanels[session.id]?.setResizeEnabled(true)

            terminalCardLayout.show(terminalPanel, session.id)
            sidebar.selectSession(session)
            terminalPanels[session.id]?.focus()
        } else {
            LOG.warn("[ClaudeCode] switchToSession: no terminal panel found for session ${session.id}")
        }
    }

    private fun closeSession(session: ClaudeCodeSession) {
        val cefPanel = terminalPanels.remove(session.id)
        val ptyBridge = ptyBridges.remove(session.id)

        if (cefPanel != null) {
            // Switch to another card before remove() so CardLayout.remove() does not call next() → reshape on the browser being torn down.
            val remainingId = terminalPanels.keys.firstOrNull()
            if (remainingId != null) {
                activeSessionId?.let { terminalPanels[it]?.setResizeEnabled(false) }
                activeSessionId = remainingId
                terminalPanels[remainingId]?.setResizeEnabled(true)
                terminalCardLayout.show(terminalPanel, remainingId)
            } else {
                activeSessionId = null
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
    }

    companion object {
        private val LOG = Logger.getInstance(ClaudeCodePanel::class.java)
    }
}
