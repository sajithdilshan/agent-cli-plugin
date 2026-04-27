package org.sajith.agentcli.plugin.session

import org.sajith.agentcli.plugin.AgentType
import java.time.LocalDateTime

data class AgentCliSession(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val agentType: AgentType = AgentType.CLAUDE,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var isActive: Boolean = true,
    val agentSessionId: String? = null,
    @Transient var needsAttention: Boolean = false,
    @Transient var attentionMessage: String? = null,
) {
    val displayName: String
        get() = name
}
