package org.sajith.claudecode.plugin.session

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ClaudeCodeSession(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var isActive: Boolean = true,
    val claudeSessionId: String? = null
) {
    val displayName: String
        get() = name

    val formattedTime: String
        get() = createdAt.format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))
}
