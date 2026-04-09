package org.sajith.agentcli.plugin.session

import org.sajith.agentcli.plugin.AgentType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class HistoricalSession(
    val sessionId: String,
    val customTitle: String,
    val firstMessage: String,
    val timestamp: LocalDateTime,
    val messageCount: Int,
    val agentType: AgentType = AgentType.CLAUDE
) {
    val displayName: String
        get() {
            if (customTitle.isNotBlank()) return customTitle

            val preview = firstMessage
                .replace("\n", " ")
                .trim()
                .take(60)
                .let { if (firstMessage.length > 60) "$it..." else it }
            return preview.ifEmpty { "Session ${sessionId.take(8)}" }
        }

    val formattedTime: String
        get() = timestamp.format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))
}