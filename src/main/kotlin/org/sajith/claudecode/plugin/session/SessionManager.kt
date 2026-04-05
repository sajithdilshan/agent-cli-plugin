package org.sajith.claudecode.plugin.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SessionManager {
    private val activeSessions = mutableListOf<ClaudeCodeSession>()
    private val resumedClaudeSessionIds = mutableSetOf<String>()
    private var sessionCounter = 0

    val sessions: List<ClaudeCodeSession> get() = activeSessions.toList()
    val openClaudeSessionIds: Set<String> get() = resumedClaudeSessionIds.toSet()

    fun createSession(name: String? = null, claudeSessionId: String? = null): ClaudeCodeSession {
        sessionCounter++
        val session = ClaudeCodeSession(
            name = name ?: "Session $sessionCounter",
            claudeSessionId = claudeSessionId
        )
        if (claudeSessionId != null) {
            resumedClaudeSessionIds.add(claudeSessionId)
        }
        activeSessions.add(session)
        return session
    }

    fun removeSession(session: ClaudeCodeSession) {
        session.isActive = false
        activeSessions.remove(session)
        session.claudeSessionId?.let { resumedClaudeSessionIds.remove(it) }
    }

    companion object {
        fun getInstance(project: Project): SessionManager {
            return project.getService(SessionManager::class.java)
        }
    }
}
