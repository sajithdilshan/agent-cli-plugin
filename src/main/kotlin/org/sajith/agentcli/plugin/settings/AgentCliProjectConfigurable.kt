package org.sajith.agentcli.plugin.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class AgentCliProjectConfigurable(project: Project) : BoundSearchableConfigurable(
    "Agent CLI",
    "org.sajith.agentcli.plugin.settings.project",
) {
    private val settings = AgentCliProjectSettings.getInstance(project)

    override fun createPanel() =
        panel {
            group("Sandbox (this project)") {
                row("Project args:") {
                    textField()
                        .columns(40)
                        .bindText(settings::sandboxProjectArgs)
                        .comment(
                            "Extra arguments substituted into the sandbox command's <code>{project_args}</code> " +
                                "placeholder for this project only (e.g. <code>--overlay my-project</code>). " +
                                "Stored in the project workspace file, not shared via VCS.",
                        )
                }
            }
        }
}
