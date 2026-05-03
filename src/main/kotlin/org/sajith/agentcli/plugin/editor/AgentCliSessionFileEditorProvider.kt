package org.sajith.agentcli.plugin.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AgentCliSessionFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = file is AgentCliSessionVirtualFile

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor {
        require(file is AgentCliSessionVirtualFile)
        return AgentCliSessionFileEditor(project, file)
    }

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "agent-cli-session"
    }
}
