package org.sajith.agentcli.plugin.editor

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class AgentCliSessionFileIconProvider : FileIconProvider {
    override fun getIcon(
        file: VirtualFile,
        flags: Int,
        project: Project?,
    ): Icon? = if (file is AgentCliSessionVirtualFile) ICON else null

    companion object {
        private val ICON: Icon = IconLoader.getIcon("/icons/agent-cli.svg", AgentCliSessionFileIconProvider::class.java)
    }
}
