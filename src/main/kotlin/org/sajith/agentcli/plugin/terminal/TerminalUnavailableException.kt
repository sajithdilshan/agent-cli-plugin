package org.sajith.agentcli.plugin.terminal

/**
 * Thrown when an embedded terminal cannot be created because JCEF is unusable — either it is not
 * supported by the runtime, or the (out-of-process) CEF server backing it is gone, which happens
 * in long-running IDE sessions after the `cef_server` process dies.
 *
 * [message] is user facing: hosts catch this and show it instead of failing construction.
 */
class TerminalUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
