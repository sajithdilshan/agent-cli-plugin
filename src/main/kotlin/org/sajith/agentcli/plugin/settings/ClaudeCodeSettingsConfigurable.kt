package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class ClaudeCodeSettingsConfigurable : BoundSearchableConfigurable(
    "Claude Code Plugin",
    "org.sajith.claudecode.plugin.settings"
) {

    private val settings = ClaudeCodeSettings.getInstance()

    override fun createPanel() = panel {
        group("Claude Code") {
            row("Claude command:") {
                textField()
                    .columns(20)
                    .bindText(settings::claudeCommand)
                    .comment("The CLI command to launch Claude Code (e.g. cc, claude)")
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
