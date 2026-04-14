package org.sajith.agentcli.plugin.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JLabel
import javax.swing.SwingConstants

class AgentCliToolWindowFactory : ToolWindowFactory, DumbAware {
    @Deprecated("Use condition attribute in plugin.xml", level = DeprecationLevel.WARNING)
    override fun isApplicable(project: Project): Boolean = true

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        if (!JBCefApp.isSupported()) {
            LOG.warn("[AgentCLI] JCEF is not available; embedded terminal disabled")
            val label =
                JLabel(
                    "<html><center>JCEF (embedded browser) is not available.<br>" +
                        "Please use a JetBrains Runtime that includes JCEF.</center></html>",
                    SwingConstants.CENTER,
                )
            val content = ContentFactory.getInstance().createContent(label, "", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        LOG.info("[AgentCLI] createToolWindowContent START")
        val panel = AgentCliPanel(project, toolWindow, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        LOG.info("[AgentCLI] createToolWindowContent END")
    }

    companion object {
        private val LOG = Logger.getInstance(AgentCliToolWindowFactory::class.java)
    }
}
