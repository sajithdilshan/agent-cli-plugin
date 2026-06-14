package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.notify.HookInstaller
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class AgentCliSettingsConfigurable : BoundSearchableConfigurable(
    "Agent CLI",
    "org.sajith.agentcli.plugin.settings",
) {
    private val settings = AgentCliSettings.getInstance()

    override fun apply() {
        super.apply()
        ApplicationManager.getApplication().messageBus
            .syncPublisher(AgentCliSettings.SETTINGS_CHANGED_TOPIC)
            .settingsChanged()
    }

    private fun showPreviewDialog() {
        val entries = HookInstaller.preview()
        if (entries.isEmpty()) {
            Messages.showInfoMessage("Nothing to preview.", "Hook Installation Preview")
            return
        }
        HookPreviewDialog(entries).show()
    }

    private class HookPreviewDialog(
        private val entries: List<HookInstaller.PreviewEntry>,
    ) : DialogWrapper(true) {
        private val createdEditors = mutableListOf<com.intellij.openapi.editor.Editor>()

        init {
            title = "Hook Installation Preview"
            setOKButtonText("Close")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val root = JPanel(BorderLayout())
            root.preferredSize = Dimension(820, 560)
            val tabs = JBTabbedPane()
            val jsonFileType = FileTypeManager.getInstance().getFileTypeByExtension("json")
            val factory = EditorFactory.getInstance()
            for (entry in entries) {
                val document = factory.createDocument(entry.newContent)
                val editor = factory.createEditor(document, null, jsonFileType, true) as EditorEx
                val scheme = EditorColorsManager.getInstance().globalScheme
                editor.colorsScheme = scheme
                editor.highlighter =
                    EditorHighlighterFactory.getInstance().createEditorHighlighter(jsonFileType, scheme, null)
                with(editor.settings) {
                    isLineNumbersShown = true
                    isFoldingOutlineShown = true
                    isLineMarkerAreaShown = false
                    isIndentGuidesShown = true
                    additionalLinesCount = 0
                    additionalColumnsCount = 0
                    isCaretRowShown = false
                    isUseSoftWraps = false
                    isAdditionalPageAtBottom = false
                    isVirtualSpace = false
                }
                editor.isViewer = true
                createdEditors.add(editor)

                val tabPanel = JPanel(BorderLayout())
                tabPanel.add(editor.component, BorderLayout.CENTER)
                val suffix = if (entry.existed) "existing" else "new"
                val label = entry.agent.replaceFirstChar { it.uppercase() }
                tabs.addTab("$label ($suffix)", tabPanel)
                tabs.setToolTipTextAt(tabs.tabCount - 1, entry.file.toString())
            }
            root.add(tabs, BorderLayout.CENTER)
            return root
        }

        override fun dispose() {
            val factory = EditorFactory.getInstance()
            createdEditors.forEach { runCatching { factory.releaseEditor(it) } }
            createdEditors.clear()
            super.dispose()
        }

        override fun createActions() = arrayOf(okAction)
    }

    override fun createPanel() =
        panel {
            lateinit var claudeCheckbox: Cell<JCheckBox>
            group("Claude Code") {
                row("Command:") {
                    val cmdField =
                        textField()
                            .columns(20)
                            .bindText(settings::claudeCommand)
                            .comment("The CLI command to launch Claude Code (e.g. cc, claude)")
                    claudeCheckbox =
                        checkBox("Enable")
                            .bindSelected(settings::claudeEnabled)
                    cmdField.enabledIf(claudeCheckbox.selected)
                }
            }
            lateinit var cursorCheckbox: Cell<JCheckBox>
            group("Cursor Agent") {
                row("Command:") {
                    val cmdField =
                        textField()
                            .columns(20)
                            .bindText(settings::cursorCommand)
                            .comment("The CLI command to launch Cursor Agent (e.g. cursor-agent)")
                    cursorCheckbox =
                        checkBox("Enable")
                            .bindSelected(settings::cursorEnabled)
                    cmdField.enabledIf(cursorCheckbox.selected)
                }
            }
            lateinit var geminiCheckbox: Cell<JCheckBox>
            group("Gemini CLI") {
                row("Command:") {
                    val cmdField =
                        textField()
                            .columns(20)
                            .bindText(settings::geminiCommand)
                            .comment("The CLI command to launch Gemini CLI (e.g. gemini)")
                    geminiCheckbox =
                        checkBox("Enable")
                            .bindSelected(settings::geminiEnabled)
                    cmdField.enabledIf(geminiCheckbox.selected)
                }
            }
            lateinit var codexCheckbox: Cell<JCheckBox>
            group("OpenAI Codex") {
                row("Command:") {
                    val cmdField =
                        textField()
                            .columns(20)
                            .bindText(settings::codexCommand)
                            .comment("The CLI command to launch OpenAI Codex (e.g. codex)")
                    codexCheckbox =
                        checkBox("Enable")
                            .bindSelected(settings::codexEnabled)
                    cmdField.enabledIf(codexCheckbox.selected)
                }
            }
            lateinit var sandboxCheckbox: Cell<JCheckBox>
            group("Sandbox") {
                row("Command:") {
                    textField()
                        .columns(30)
                        .bindText(settings::sandboxCommand)
                        .comment(
                            "Command to launch the sandboxed agent. Use {dir} for the project path and " +
                                "{project_args} for per-project arguments (set under Settings → Tools → Agent CLI → Project). " +
                                "Example: claude-crate --workdir {dir} {project_args}",
                        )
                }
                row("History dir:") {
                    textField()
                        .columns(30)
                        .bindText(settings::sandboxHistoryDir)
                        .comment("Base dir where the sandbox stores agent history (e.g. ~/.claude-crate)")
                }
                row("Runs as:") {
                    comboBox(
                        AgentType.entries.filter { it != AgentType.SANDBOX },
                        SimpleListCellRenderer.create("") { it.displayName },
                    )
                        .bindItem(
                            { settings.sandboxUnderlyingAgent },
                            { settings.sandboxUnderlyingAgent = it ?: AgentType.CLAUDE },
                        )
                        .comment("The agent running inside the sandbox — selects history parsing and resume syntax")
                }
                row {
                    sandboxCheckbox =
                        checkBox("Enable")
                            .bindSelected(settings::sandboxEnabled)
                }
            }
            group("Attention Notifications") {
                row {
                    comment(
                        "Install hooks into ~/.claude/settings.json, ~/.gemini/settings.json, and ~/.codex/hooks.json " +
                            "so this plugin is notified when an agent is waiting for you (red dot in the sessions panel + IDE balloon). " +
                            "A .bak copy of each existing file is saved alongside before any changes are written.",
                    )
                }
                lateinit var installBtn: Cell<javax.swing.JButton>
                lateinit var uninstallBtn: Cell<javax.swing.JButton>
                lateinit var statusLabel: Cell<javax.swing.JLabel>

                fun refreshStatus() {
                    val st = HookInstaller.status()
                    installBtn.component.isEnabled = st != HookInstaller.Status.INSTALLED
                    uninstallBtn.component.isEnabled = st != HookInstaller.Status.NOT_INSTALLED
                    statusLabel.component.text =
                        when (st) {
                            HookInstaller.Status.INSTALLED -> "Status: installed"
                            HookInstaller.Status.NOT_INSTALLED -> "Status: not installed"
                            HookInstaller.Status.PARTIAL -> "Status: partially installed — click Install to complete"
                        }
                }

                row {
                    button("Preview…") { showPreviewDialog() }
                    installBtn =
                        button("Install Hooks") {
                            val result = HookInstaller.install()
                            if (result.success) {
                                val body =
                                    buildString {
                                        append("Wrote notify script to:\n  ").append(result.scriptPath).append("\n\n")
                                        append("Updated:\n")
                                        result.configs.forEach { append("  ").append(it).append('\n') }
                                        if (result.backups.isNotEmpty()) {
                                            append("\nBackups:\n")
                                            result.backups.forEach { append("  ").append(it).append('\n') }
                                        }
                                    }
                                Messages.showInfoMessage(body, "Hooks Installed")
                            } else {
                                Messages.showErrorDialog("Install failed: ${result.error}", "Agent CLI")
                            }
                            refreshStatus()
                        }
                    uninstallBtn =
                        button("Uninstall") {
                            val result = HookInstaller.uninstall()
                            if (result.success) {
                                val detail =
                                    buildString {
                                        if (result.configs.isEmpty()) {
                                            append("No hook entries needed to be removed.")
                                        } else {
                                            append("Cleaned:\n")
                                            result.configs.forEach { append("  ").append(it).append('\n') }
                                            if (result.backups.isNotEmpty()) {
                                                append("\nBackups:\n")
                                                result.backups.forEach { append("  ").append(it).append('\n') }
                                            }
                                        }
                                        if (result.scriptDeleted) {
                                            append("\nDeleted notify script:\n  ").append(result.scriptPath).append('\n')
                                        }
                                    }
                                Messages.showInfoMessage(detail, "Hooks Uninstalled")
                            } else {
                                Messages.showErrorDialog("Uninstall failed: ${result.error}", "Agent CLI")
                            }
                            refreshStatus()
                        }
                }
                row {
                    statusLabel = label("")
                }
                onApply { refreshStatus() }
                refreshStatus()
            }
            group("Terminal") {
                row("Font size:") {
                    intTextField(8..32)
                        .columns(5)
                        .bindIntText(settings::terminalFontSize)
                        .comment("Terminal font size (8-32)")
                }
                row {
                    checkBox("Enable flow control")
                        .bindSelected(settings::flowControlEnabled)
                        .comment("Throttle fast PTY output to prevent terminal buffer overflows (restart session to apply)")
                }
            }
            group("Session Placement") {
                row {
                    checkBox("Always open new sessions in code editor")
                        .bindSelected(settings::alwaysOpenNewSessionInEditor)
                        .comment(
                            "When enabled, new sessions open as editor tabs instead of in the tool window.",
                        )
                }
                row {
                    checkBox("Always resume historical sessions in code editor")
                        .bindSelected(settings::alwaysResumeSessionInEditor)
                        .comment(
                            "When enabled, double-clicking a session in history opens it as an editor tab; " +
                                "the right-click menu then offers \"Open in Plugin View\" instead.",
                        )
                }
            }
        }
}
