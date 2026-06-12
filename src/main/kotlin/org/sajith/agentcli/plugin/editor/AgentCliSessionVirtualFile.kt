package org.sajith.agentcli.plugin.editor

import com.intellij.testFramework.LightVirtualFile
import org.sajith.agentcli.plugin.AgentType
import java.util.*

/**
 * Virtual file that represents an agent CLI session hosted inside an IntelliJ editor tab.
 *
 * For resumed sessions [agentSessionId] is set and doubles as the identity key; for
 * brand-new sessions (launched directly into the editor when the
 * "Always open new sessions in code editor" setting is on) [agentSessionId] is null
 * and a random [key] disambiguates two concurrent new sessions.
 */
class AgentCliSessionVirtualFile private constructor(
    val agentType: AgentType,
    val agentSessionId: String?,
    val key: String,
    val displayName: String,
) : LightVirtualFile(displayName) {
    init {
        isWritable = false
    }

    override fun getPath(): String = "agent-cli-session://${agentType.name}/$key"

    override fun getPresentableUrl(): String = displayName

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentCliSessionVirtualFile) return false
        return agentType == other.agentType && key == other.key
    }

    override fun hashCode(): Int = 31 * agentType.hashCode() + key.hashCode()

    companion object {
        fun forResume(
            agentType: AgentType,
            agentSessionId: String,
            displayName: String,
        ): AgentCliSessionVirtualFile = AgentCliSessionVirtualFile(agentType, agentSessionId, agentSessionId, displayName)

        fun forNewSession(
            agentType: AgentType,
            displayName: String,
        ): AgentCliSessionVirtualFile = AgentCliSessionVirtualFile(agentType, null, "new-${UUID.randomUUID()}", displayName)
    }
}
