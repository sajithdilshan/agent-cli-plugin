package org.sajith.agentcli.plugin.editor

import com.intellij.testFramework.LightVirtualFile
import org.sajith.agentcli.plugin.AgentType

/**
 * Virtual file that represents an agent CLI session hosted inside an IntelliJ editor tab.
 *
 * Two files with the same [agentSessionId] + [agentType] are considered equal so that
 * re-opening the same session focuses the existing tab instead of spawning a duplicate.
 */
class AgentCliSessionVirtualFile(
    val agentType: AgentType,
    val agentSessionId: String,
    val displayName: String,
) : LightVirtualFile(displayName) {
    init {
        isWritable = false
    }

    override fun getPath(): String = "agent-cli-session://${agentType.name}/$agentSessionId"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentCliSessionVirtualFile) return false
        return agentType == other.agentType && agentSessionId == other.agentSessionId
    }

    override fun hashCode(): Int = 31 * agentType.hashCode() + agentSessionId.hashCode()
}
