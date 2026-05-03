package org.sajith.agentcli.plugin.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.jetbrains.ide.BuiltInServerManager
import org.sajith.agentcli.plugin.notify.SessionAttentionService
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Shared wiring of [CefTerminalPanel] + [TerminalFlowController] + [PtyBridge] for a
 * single [AgentCliSession]. Used both by the tool-window panel and by the editor-hosted
 * view, so the PTY env (session id, notify URL, UTF-8 locale) stays in one place.
 */
class EmbeddedAgentTerminal(
    parentDisposable: Disposable,
    project: Project,
    private val session: AgentCliSession,
    workingDirectory: String,
    command: String,
    isResume: Boolean,
    onExit: (exitCode: Int) -> Unit,
) {
    val cefPanel: CefTerminalPanel
    private val ptyBridge: PtyBridge
    private val flowController: TerminalFlowController

    val component: JComponent get() = cefPanel.component

    init {
        val attentionService = SessionAttentionService.getInstance(project)

        lateinit var ptyBridgeRef: PtyBridge
        lateinit var flowControllerRef: TerminalFlowController

        cefPanel =
            CefTerminalPanel(
                parentDisposable = parentDisposable,
                onInput = { data ->
                    ptyBridgeRef.write(data)
                    attentionService.clearByPluginSessionId(session.id)
                },
                onResize = { cols, rows -> ptyBridgeRef.resize(cols, rows) },
                onAck = { flowControllerRef.ack() },
                loadingText = if (isResume) "Resuming Session..." else "Starting Session...",
            )

        val flowControlEnabled = AgentCliSettings.getInstance().flowControlEnabled
        flowControllerRef =
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
                onPause = { if (flowControlEnabled) ptyBridgeRef.pause() },
                onResume = { if (flowControlEnabled) ptyBridgeRef.resume() },
            )
        flowController = flowControllerRef

        val shellCommand = shellCommandFor(command)
        val env = buildEnv(session)

        ptyBridgeRef =
            PtyBridge(
                command = shellCommand,
                workingDirectory = workingDirectory,
                environment = env,
                onOutput = { data -> flowControllerRef.write(data) },
                onExit = { exitCode ->
                    LOG.info("[AgentCLI] Agent process exited with code $exitCode for session ${session.id}")
                    SwingUtilities.invokeLater { onExit(exitCode) }
                },
            )
        ptyBridge = ptyBridgeRef
        Disposer.register(parentDisposable, ptyBridge)
    }

    fun start() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ptyBridge.start()
            } catch (e: Exception) {
                LOG.error("[AgentCLI] Failed to start PTY for session ${session.id}", e)
            }
        }
    }

    fun setResizeEnabled(enabled: Boolean) = cefPanel.setResizeEnabled(enabled)

    fun focus() = cefPanel.focus()

    fun applyTheme() = cefPanel.applyTheme()

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

    private fun buildEnv(session: AgentCliSession): Map<String, String> {
        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        env["AGENT_CLI_PLUGIN_SESSION_ID"] = session.id
        env["AGENT_CLI_PLUGIN_AGENT"] = session.agentType.name.lowercase()
        val port = BuiltInServerManager.getInstance().port
        env["AGENT_CLI_PLUGIN_NOTIFY_URL"] = "http://127.0.0.1:$port/agent-cli-plugin/notify"

        // Ensure the PTY locale is UTF-8 so programs interpret I/O correctly.
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
        return env
    }

    companion object {
        private val LOG = Logger.getInstance(EmbeddedAgentTerminal::class.java)
    }
}
