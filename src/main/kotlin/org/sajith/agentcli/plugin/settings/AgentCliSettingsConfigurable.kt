package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class AgentCliSettingsConfigurable : BoundSearchableConfigurable(
    "Agent CLI",
    "org.sajith.agentcli.plugin.settings",
) {
    private val settings = AgentCliSettings.getInstance()

    override fun createPanel() =
        panel {
            group("Claude Code") {
                row("Claude command:") {
                    textField()
                        .columns(20)
                        .bindText(settings::claudeCommand)
                        .comment("The CLI command to launch Claude Code (e.g. cc, claude)")
                }
            }
            group("Cursor Agent") {
                row("Cursor command:") {
                    textField()
                        .columns(20)
                        .bindText(settings::cursorCommand)
                        .comment("The CLI command to launch Cursor Agent (e.g. cursor-agent)")
                }
            }
            group("Gemini CLI") {
                row("Gemini command:") {
                    textField()
                        .columns(20)
                        .bindText(settings::geminiCommand)
                        .comment("The CLI command to launch Gemini CLI (e.g. gemini)")
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
