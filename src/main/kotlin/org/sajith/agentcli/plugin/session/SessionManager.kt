package org.sajith.agentcli.plugin.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import org.sajith.agentcli.plugin.AgentType

@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {
    private val activeSessions = mutableListOf<AgentCliSession>()
    private val openSessionIds = mutableMapOf<AgentType, MutableSet<String>>()
    private var sessionCounter = 0

    val sessions: List<AgentCliSession> get() = activeSessions.toList()

    fun getOpenSessionIds(agentType: AgentType): Set<String> = openSessionIds[agentType]?.toSet() ?: emptySet()

    fun createSession(
        name: String? = null,
        agentType: AgentType = AgentType.CLAUDE,
        agentSessionId: String? = null,
        isEditorHosted: Boolean = false,
    ): AgentCliSession {
        sessionCounter++
        val session =
            AgentCliSession(
                name = name ?: "Session $sessionCounter",
                agentType = agentType,
                agentSessionId = agentSessionId,
                isEditorHosted = isEditorHosted,
            )
        if (agentSessionId != null) {
            openSessionIds.getOrPut(agentType) { mutableSetOf() }.add(agentSessionId)
        }
        activeSessions.add(session)
        project.messageBus.syncPublisher(SESSION_LIFECYCLE_TOPIC).sessionAdded(session)
        return session
    }

    fun removeSession(session: AgentCliSession) {
        session.isActive = false
        session.needsAttention = false
        session.attentionMessage = null
        activeSessions.remove(session)
        session.agentSessionId?.let { openSessionIds[session.agentType]?.remove(it) }
        project.messageBus.syncPublisher(SESSION_LIFECYCLE_TOPIC).sessionRemoved(session)
    }

    fun findById(sessionId: String): AgentCliSession? = activeSessions.firstOrNull { it.id == sessionId }

    fun findByAgentSessionId(agentSessionId: String): AgentCliSession? = activeSessions.firstOrNull { it.agentSessionId == agentSessionId }

    fun markAttention(
        sessionId: String,
        message: String?,
    ): AgentCliSession? {
        val session = findById(sessionId) ?: findByAgentSessionId(sessionId) ?: return null
        session.needsAttention = true
        session.attentionMessage = message
        fireAttentionChanged(session)
        return session
    }

    fun clearAttention(sessionId: String): AgentCliSession? {
        val session = findById(sessionId) ?: findByAgentSessionId(sessionId) ?: return null
        if (!session.needsAttention && session.attentionMessage == null) return session
        session.needsAttention = false
        session.attentionMessage = null
        fireAttentionChanged(session)
        return session
    }

    private fun fireAttentionChanged(session: AgentCliSession) {
        project.messageBus.syncPublisher(SESSION_ATTENTION_TOPIC).attentionChanged(session)
    }

    fun interface SessionAttentionListener {
        fun attentionChanged(session: AgentCliSession)
    }

    interface SessionLifecycleListener {
        fun sessionAdded(session: AgentCliSession) {}

        fun sessionRemoved(session: AgentCliSession) {}
    }

    companion object {
        val SESSION_ATTENTION_TOPIC: Topic<SessionAttentionListener> =
            Topic.create("AgentCLI.SessionAttention", SessionAttentionListener::class.java)

        val SESSION_LIFECYCLE_TOPIC: Topic<SessionLifecycleListener> =
            Topic.create("AgentCLI.SessionLifecycle", SessionLifecycleListener::class.java)

        fun getInstance(project: Project): SessionManager = project.getService(SessionManager::class.java)
    }
}
