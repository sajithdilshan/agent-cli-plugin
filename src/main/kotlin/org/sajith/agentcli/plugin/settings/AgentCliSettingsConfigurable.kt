package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import org.sajith.agentcli.plugin.notify.HookInstaller
import javax.swing.JCheckBox

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
        val message =
            buildString {
                append("The following files will be created/updated:\n\n")
                for (entry in entries) {
                    append("── ").append(entry.file).append(if (entry.existed) " (existing)" else " (new)").append(" ──\n")
                    append(entry.newContent).append("\n\n")
                }
            }
        Messages.showInfoMessage(message, "Hook Installation Preview")
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
        }
}
