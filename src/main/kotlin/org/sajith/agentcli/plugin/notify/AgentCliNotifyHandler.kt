package org.sajith.agentcli.plugin.notify

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import io.netty.util.CharsetUtil
import org.jetbrains.ide.HttpRequestHandler
import org.sajith.agentcli.plugin.session.SessionManager

/**
 * Receives POSTs from per-agent notification hook scripts on the IntelliJ built-in web server
 * (see {@link org.jetbrains.ide.BuiltInServerManager}) at:
 *
 *   POST /agent-cli-plugin/notify
 *   Content-Type: application/json
 *   Body: {
 *     "plugin_session_id": "<AgentCliSession.id>",  // preferred; injected via PTY env
 *     "session_id":        "<agent-native id>",      // fallback
 *     "event":             "set" | "clear",
 *     "message":           "<optional message>",
 *     "agent":             "claude" | "codex" | "cursor"
 *   }
 *
 * Returns 204 on success, 400 on malformed request.
 */
class AgentCliNotifyHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.method() == HttpMethod.POST &&
            request.uri().startsWith("/agent-cli-plugin/notify")
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val body = request.content().toString(CharsetUtil.UTF_8)
        val obj: JsonObject? =
            runCatching { JsonParser.parseString(body) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject

        if (obj == null) {
            LOG.warn("[AgentCLI] notify: body is not a JSON object: $body")
            respond(context, request, HttpResponseStatus.BAD_REQUEST)
            return true
        }

        val pluginSessionId = obj.optString("plugin_session_id")
        val agentSessionId = obj.optString("session_id")
        val event = obj.optString("event") ?: "set"
        val message = obj.optString("message") ?: obj.optString("notification_type")
        val agent = obj.optString("agent")

        if (pluginSessionId == null && agentSessionId == null) {
            LOG.warn("[AgentCLI] notify: missing session id in body: $body")
            respond(context, request, HttpResponseStatus.BAD_REQUEST)
            return true
        }

        ApplicationManager.getApplication().invokeLater {
            dispatch(pluginSessionId, agentSessionId, event, message, agent)
        }
        respond(context, request, HttpResponseStatus.NO_CONTENT)
        return true
    }

    private fun JsonObject.optString(key: String): String? {
        if (!has(key)) return null
        val el = get(key)
        if (el == null || el.isJsonNull) return null
        if (!el.isJsonPrimitive) return null
        val s = el.asString
        return s.takeIf { it.isNotBlank() }
    }

    private fun dispatch(
        pluginSessionId: String?,
        agentSessionId: String?,
        event: String,
        message: String?,
        agent: String?,
    ) {
        val projects = ProjectManager.getInstance().openProjects
        for (project in projects) {
            if (project.isDisposed) continue
            val manager = SessionManager.getInstance(project)
            val hasSession =
                (pluginSessionId != null && manager.findById(pluginSessionId) != null) ||
                    (agentSessionId != null && manager.findByAgentSessionId(agentSessionId) != null)
            if (!hasSession) continue
            val service = SessionAttentionService.getInstance(project)
            when (event.lowercase()) {
                "clear" -> service.onClear(pluginSessionId, agentSessionId)
                else -> service.onNotify(pluginSessionId, agentSessionId, message, agent)
            }
            return
        }
        LOG.warn(
            "[AgentCLI] notify: no open project owns session plugin=$pluginSessionId agent=$agentSessionId",
        )
    }

    private fun respond(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        status: HttpResponseStatus,
    ) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER)
        response.headers()
            .set(HttpHeaderNames.CONTENT_LENGTH, 0)
            .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
        val keepAlive =
            HttpHeaderValues.KEEP_ALIVE.contentEqualsIgnoreCase(
                request.headers().get(HttpHeaderNames.CONNECTION),
            )
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
            context.writeAndFlush(response)
        } else {
            context.writeAndFlush(response).addListener { context.close() }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(AgentCliNotifyHandler::class.java)
    }
}
