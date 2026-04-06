package org.sajith.agentcli.plugin.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.content.ContentFactory
import javax.swing.JLabel
import javax.swing.SwingConstants

class ClaudeCodeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            LOG.warn("[ClaudeCode] JCEF is not available; embedded terminal disabled")
            val label = JLabel(
                "<html><center>JCEF (embedded browser) is not available.<br>" +
                        "Please use a JetBrains Runtime that includes JCEF.</center></html>",
                SwingConstants.CENTER
            )
            val content = ContentFactory.getInstance().createContent(label, "", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        val panel = ClaudeCodePanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        panel.createNewSession()
    }

    companion object {
        private val LOG = Logger.getInstance(ClaudeCodeToolWindowFactory::class.java)
    }
}
