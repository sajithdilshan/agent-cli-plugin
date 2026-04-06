package org.sajith.agentcli.plugin.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.sajith.agentcli.plugin.AgentType

@Service(Service.Level.PROJECT)
class SessionManager {
    private val activeSessions = mutableListOf<AgentCliSession>()
    private val openSessionIds = mutableMapOf<AgentType, MutableSet<String>>()
    private var sessionCounter = 0

    val sessions: List<AgentCliSession> get() = activeSessions.toList()

    fun getOpenSessionIds(agentType: AgentType): Set<String> =
        openSessionIds[agentType]?.toSet() ?: emptySet()

    fun createSession(
        name: String? = null,
        agentType: AgentType = AgentType.CLAUDE,
        agentSessionId: String? = null
    ): AgentCliSession {
        sessionCounter++
        val session = AgentCliSession(
            name = name ?: "Session $sessionCounter",
            agentType = agentType,
            agentSessionId = agentSessionId
        )
        if (agentSessionId != null) {
            openSessionIds.getOrPut(agentType) { mutableSetOf() }.add(agentSessionId)
        }
        activeSessions.add(session)
        return session
    }

    fun removeSession(session: AgentCliSession) {
        session.isActive = false
        activeSessions.remove(session)
        session.agentSessionId?.let { openSessionIds[session.agentType]?.remove(it) }
    }

    companion object {
        fun getInstance(project: Project): SessionManager {
            return project.getService(SessionManager::class.java)
        }
    }
}
