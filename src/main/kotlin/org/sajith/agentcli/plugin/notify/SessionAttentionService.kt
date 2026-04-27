package org.sajith.agentcli.plugin.notify

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.SystemNotifications
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.session.SessionManager

/**
 * Routes "needs attention" / "cleared" events from the HTTP endpoint to the SessionManager
 * and surfaces IntelliJ balloon notifications.
 */
@Service(Service.Level.PROJECT)
class SessionAttentionService(private val project: Project) {
    private val sessionManager get() = SessionManager.getInstance(project)

    fun onNotify(
        pluginSessionId: String?,
        agentSessionId: String?,
        message: String?,
        agent: String?,
    ): Boolean {
        val sessionId = pluginSessionId?.takeIf { it.isNotBlank() } ?: agentSessionId?.takeIf { it.isNotBlank() }
        if (sessionId == null) {
            LOG.warn("[AgentCLI] onNotify: no session id in payload")
            return false
        }
        val session = sessionManager.markAttention(sessionId, message)
        if (session == null) {
            LOG.warn("[AgentCLI] onNotify: no active session matches id=$sessionId")
            return false
        }
        showBalloon(session, message ?: "Needs your attention", agent)
        return true
    }

    fun onClear(
        pluginSessionId: String?,
        agentSessionId: String?,
    ): Boolean {
        val sessionId = pluginSessionId?.takeIf { it.isNotBlank() } ?: agentSessionId?.takeIf { it.isNotBlank() } ?: return false
        sessionManager.clearAttention(sessionId)
        return true
    }

    /** Clear by the plugin-side session id. Used for focus/typing auto-clear. */
    fun clearByPluginSessionId(pluginSessionId: String) {
        sessionManager.clearAttention(pluginSessionId)
    }

    private fun showBalloon(
        session: AgentCliSession,
        message: String,
        agent: String?,
    ) {
        val agentLabel = agent?.takeIf { it.isNotBlank() } ?: session.agentType.displayName
        val title = "$agentLabel · ${session.displayName}"
        val notification =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, message, NotificationType.INFORMATION)
                .addAction(
                    NotificationAction.createSimple("Mark as seen") {
                        sessionManager.clearAttention(session.id)
                    },
                )
        notification.notify(project)
        // Also post to the OS notification center (macOS banner / Windows toast / Linux libnotify).
        // IntelliJ's own balloons only bridge to the OS for "important" groups and only when the
        // IDE is in the background, so we call this unconditionally to guarantee a banner.
        SystemNotifications.getInstance().notify(NOTIFICATION_GROUP_ID, title, message)
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "Agent CLI Attention"
        private val LOG = Logger.getInstance(SessionAttentionService::class.java)

        fun getInstance(project: Project): SessionAttentionService = project.getService(SessionAttentionService::class.java)
    }
}
