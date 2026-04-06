package org.sajith.agentcli.plugin.session

import org.sajith.agentcli.plugin.AgentType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class AgentCliSession(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val agentType: AgentType = AgentType.CLAUDE,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var isActive: Boolean = true,
    val agentSessionId: String? = null
) {
    val displayName: String
        get() = name

    val formattedTime: String
        get() = createdAt.format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))
}
