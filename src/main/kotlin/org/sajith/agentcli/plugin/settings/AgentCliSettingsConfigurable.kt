package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
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
            group("Terminal") {
                row("Font size:") {
                    intTextField(8..32)
                        .columns(5)
                        .bindIntText(settings::terminalFontSize)
                        .comment("Terminal font size (8-32)")
                }
            }
        }
}
