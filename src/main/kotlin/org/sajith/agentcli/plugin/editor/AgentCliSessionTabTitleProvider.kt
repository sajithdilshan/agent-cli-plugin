package org.sajith.agentcli.plugin.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Supplies a human-readable tab title for session tabs. Without this the platform falls
 * back to the file's presentable URL (`agent-cli-session://TYPE/<key>`). The tooltip comes
 * from [AgentCliSessionVirtualFile.getPresentableUrl].
 */
class AgentCliSessionTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(
        project: Project,
        file: VirtualFile,
    ): String? = (file as? AgentCliSessionVirtualFile)?.displayName
}
