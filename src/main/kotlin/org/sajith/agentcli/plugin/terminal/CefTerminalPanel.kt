package org.sajith.agentcli.plugin.terminal

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.net.URI
import java.util.Base64
import javax.swing.JComponent
import javax.swing.Timer

class CefTerminalPanel(
    parentDisposable: Disposable,
    private val onInput: (String) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit,
    private val onAck: () -> Unit = {},
    private val loadingText: String = "Starting Session...",
    private val sandbox: Boolean = false,
) : Disposable {
    private val browser: JBCefBrowser
    private val inputQuery: JBCefJSQuery
    private val resizeQuery: JBCefJSQuery
    private val openLinkQuery: JBCefJSQuery
    private val ackQuery: JBCefJSQuery

    @Volatile
    private var isPageLoaded = false

    private data class PendingWrite(val base64: String, val needsAck: Boolean)

    private val pendingWrites = mutableListOf<PendingWrite>()

    @Volatile
    private var pendingFocus = false

    @Volatile
    private var resizeEnabledState = false

    /** Swing timer to debounce componentResized events. */
    private val resizeDebounceTimer = Timer(200) { onResizeSettled() }.apply { isRepeats = false }

    private var resizeListener: ComponentAdapter? = null

    val component: JComponent get() = browser.component

    init {
        // The browser and the JS bridges are created up front and separately from the rest of
        // the wiring: creating them talks to JCEF (in recent IDEs an out-of-process CEF server)
        // and can fail — e.g. when that server process died in a long-running IDE session.
        // Anything already created is torn down before the failure is rethrown so a failed
        // session never leaks native objects.
        val bridges = createBridges()
        browser = bridges.browser
        inputQuery = bridges.inputQuery
        resizeQuery = bridges.resizeQuery
        openLinkQuery = bridges.openLinkQuery
        ackQuery = bridges.ackQuery

        try {
            wireHandlersAndLoadPage(parentDisposable)
        } catch (t: Throwable) {
            disposeCefResources()
            throw t
        }
    }

    private fun wireHandlersAndLoadPage(parentDisposable: Disposable) {
        inputQuery.addHandler { base64Data ->
            try {
                val decoded = String(Base64.getDecoder().decode(base64Data), Charsets.UTF_8)
                onInput(decoded)
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] CefTerminalPanel: failed to decode input from JS", e)
            }
            JBCefJSQuery.Response("")
        }

        resizeQuery.addHandler { sizeJson ->
            try {
                val dimensions = parseResizeDimensions(sizeJson)
                if (dimensions != null) {
                    val (cols, rows) = dimensions
                    onResize(cols, rows)
                } else {
                    LOG.warn("[AgentCLI] CefTerminalPanel: invalid resize data: $sizeJson")
                }
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] CefTerminalPanel: failed to parse resize from JS: $sizeJson", e)
            }
            JBCefJSQuery.Response("")
        }

        openLinkQuery.addHandler { url ->
            try {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    BrowserUtil.browse(uri)
                } else {
                    LOG.warn("[AgentCLI] CefTerminalPanel: blocked non-http(s) link: $url")
                }
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] CefTerminalPanel: invalid link URL: $url", e)
            }
            JBCefJSQuery.Response("")
        }

        ackQuery.addHandler {
            try {
                onAck()
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] CefTerminalPanel: ack handler error", e)
            }
            JBCefJSQuery.Response("")
        }

        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    browser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int,
                ) {
                    if (frame.isMain) {
                        isPageLoaded = true
                        applyTheme()
                        flushPendingWrites()
                        if (pendingFocus) {
                            pendingFocus = false
                            executeJsFocusTerminal()
                        }
                    }
                }

                override fun onLoadError(
                    browser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String,
                ) {
                    LOG.error("[AgentCLI] CefTerminalPanel: page load error: code=$errorCode text='$errorText' url='$failedUrl'")
                }
            },
            browser.cefBrowser,
        )

        browser.jbCefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser,
                    level: CefSettings.LogSeverity,
                    message: String,
                    source: String,
                    line: Int,
                ): Boolean {
                    when (level) {
                        CefSettings.LogSeverity.LOGSEVERITY_ERROR,
                        CefSettings.LogSeverity.LOGSEVERITY_FATAL,
                        ->
                            LOG.error("[AgentCLI] JS: $message ($source:$line)")
                        CefSettings.LogSeverity.LOGSEVERITY_WARNING ->
                            LOG.warn("[AgentCLI] JS: $message ($source:$line)")
                        else -> {}
                    }
                    return false
                }
            },
            browser.cefBrowser,
        )

        // Listen to Swing component resize — this fires when the IDE resizes the tool window.
        // We debounce and only call JS once the resize gesture settles.
        val listener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (!resizeEnabledState || !isPageLoaded) return
                    resizeDebounceTimer.restart()
                }
            }
        resizeListener = listener
        browser.component.addComponentListener(listener)

        val html = buildTerminalHtml()
        browser.loadHTML(html)

        Disposer.register(parentDisposable, this)
    }

    private fun onResizeSettled() {
        if (!isPageLoaded || !resizeEnabledState) return
        executeJs("window.fitAndRestore()")
    }

    private fun buildTerminalHtml(): String {
        val fontSize = AgentCliSettings.getInstance().terminalFontSize
        val assets = TERMINAL_ASSETS

        val inputQueryJs = inputQuery.inject("base64")
        val resizeQueryJs = resizeQuery.inject("size")
        val openLinkQueryJs = openLinkQuery.inject("uri")
        val ackQueryJs = ackQuery.inject("ack")

        return buildCefTerminalPageHtml(
            fontSize = fontSize,
            fontFaceCss = assets.fontFaceCss,
            xtermCss = assets.xtermCss,
            xtermJs = assets.xtermJs,
            fitAddonJs = assets.fitAddonJs,
            webLinksAddonJs = assets.webLinksAddonJs,
            unicode11AddonJs = assets.unicode11AddonJs,
            inputQueryJs = inputQueryJs,
            resizeQueryJs = resizeQueryJs,
            openLinkQueryJs = openLinkQueryJs,
            ackQueryJs = ackQueryJs,
            loadingText = loadingText,
            sandbox = sandbox,
        )
    }

    /** Fast-path write: no ack callback. */
    fun writeToTerminal(data: ByteArray) {
        val base64 = Base64.getEncoder().encodeToString(data)
        if (isPageLoaded) {
            executeJs("window.writeTerminalData('$base64')")
        } else {
            synchronized(pendingWrites) {
                pendingWrites.add(PendingWrite(base64, needsAck = false))
            }
        }
    }

    /** Ack-path write: xterm.js will signal back when this chunk is processed. */
    fun writeToTerminalAck(data: ByteArray) {
        val base64 = Base64.getEncoder().encodeToString(data)
        if (isPageLoaded) {
            executeJs("window.writeTerminalDataAck('$base64')")
        } else {
            synchronized(pendingWrites) {
                pendingWrites.add(PendingWrite(base64, needsAck = true))
            }
        }
    }

    fun applyTheme() {
        val themeJson = TerminalThemeProvider.getThemeJson()
        val base64 = Base64.getEncoder().encodeToString(themeJson.toByteArray(Charsets.UTF_8))
        executeJs("window.setTerminalTheme('$base64')")
    }

    fun setResizeEnabled(enabled: Boolean) {
        resizeEnabledState = enabled
        if (!enabled) {
            resizeDebounceTimer.stop()
        }
    }

    fun focus() {
        if (isPageLoaded) {
            executeJsFocusTerminal()
        } else {
            pendingFocus = true
        }
    }

    private fun executeJsFocusTerminal() {
        executeJs("window.focusTerminal()")
    }

    private fun flushPendingWrites() {
        val writes: List<PendingWrite>
        synchronized(pendingWrites) {
            writes = pendingWrites.toList()
            pendingWrites.clear()
        }
        for (pw in writes) {
            if (pw.needsAck) {
                executeJs("window.writeTerminalDataAck('${pw.base64}')")
            } else {
                executeJs("window.writeTerminalData('${pw.base64}')")
            }
        }
    }

    private fun executeJs(code: String) {
        browser.cefBrowser.executeJavaScript(code, browser.cefBrowser.url ?: "about:blank", 0)
    }

    override fun dispose() {
        resizeDebounceTimer.stop()
        resizeListener?.let { browser.component.removeComponentListener(it) }
        disposeCefResources()
    }

    private fun disposeCefResources() {
        // Best-effort: when JCEF is already broken, disposing one bridge must not stop the rest.
        listOf(inputQuery, resizeQuery, openLinkQuery, ackQuery, browser).forEach {
            try {
                Disposer.dispose(it)
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] CefTerminalPanel: failed to dispose JCEF resource", e)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CefTerminalPanel::class.java)

        private class Bridges(
            val browser: JBCefBrowser,
            val inputQuery: JBCefJSQuery,
            val resizeQuery: JBCefJSQuery,
            val openLinkQuery: JBCefJSQuery,
            val ackQuery: JBCefJSQuery,
        )

        /**
         * Creates the JCEF browser and the four JS↔JVM bridges, or throws
         * [TerminalUnavailableException] if JCEF cannot serve them. Partial results are disposed
         * before throwing.
         */
        private fun createBridges(): Bridges {
            if (!JBCefApp.isSupported()) {
                throw TerminalUnavailableException(
                    "JCEF (the IDE's embedded browser) is not available in this runtime.",
                )
            }

            var browser: JBCefBrowser? = null
            val queries = mutableListOf<JBCefJSQuery>()
            try {
                val created = JBCefBrowser()
                browser = created
                repeat(4) { queries.add(JBCefJSQuery.create(created as JBCefBrowserBase)) }
                return Bridges(created, queries[0], queries[1], queries[2], queries[3])
            } catch (t: Throwable) {
                (queries + listOfNotNull(browser)).forEach {
                    try {
                        Disposer.dispose(it)
                    } catch (e: Exception) {
                        LOG.warn("[AgentCLI] CefTerminalPanel: cleanup after failed JCEF init", e)
                    }
                }
                if (t is ProcessCanceledException) throw t
                throw TerminalUnavailableException(
                    "The IDE's embedded browser (JCEF) is not responding. Restarting the IDE usually fixes this.",
                    t,
                )
            }
        }

        private data class TerminalAssets(
            val xtermJs: String,
            val xtermCss: String,
            val fitAddonJs: String,
            val webLinksAddonJs: String,
            val unicode11AddonJs: String,
            val fontFaceCss: String,
        )

        /**
         * xterm resources and embedded font are immutable; loading them once avoids repeated
         * disk IO and base64 encoding whenever users open additional sessions.
         */
        private val TERMINAL_ASSETS: TerminalAssets by lazy(LazyThreadSafetyMode.PUBLICATION) {
            TerminalAssets(
                xtermJs = readResource("/terminal/xterm.js"),
                xtermCss = readResource("/terminal/xterm.css"),
                fitAddonJs = readResource("/terminal/xterm-addon-fit.js"),
                webLinksAddonJs = readResource("/terminal/xterm-addon-web-links.js"),
                unicode11AddonJs = readResource("/terminal/xterm-addon-unicode11.js"),
                fontFaceCss = buildFontFaceCss(),
            )
        }

        private val RESIZE_COLS_REGEX = Regex(""""cols"\s*:\s*(\d+)""")
        private val RESIZE_ROWS_REGEX = Regex(""""rows"\s*:\s*(\d+)""")

        private fun parseResizeDimensions(sizeJson: String): Pair<Int, Int>? {
            val cols = RESIZE_COLS_REGEX.find(sizeJson)?.groupValues?.get(1)?.toInt()
            val rows = RESIZE_ROWS_REGEX.find(sizeJson)?.groupValues?.get(1)?.toInt()
            return if (cols != null && rows != null && cols > 0 && rows > 0) cols to rows else null
        }

        private fun buildFontFaceCss(): String {
            val regularBase64 = readResourceBase64("/fonts/InconsolataNerdFontMono-Regular.ttf")
            // Use the regular font for both normal and bold weights to keep glyph widths
            // identical across the monospace grid. The browser synthesizes faux-bold.
            return """
@font-face {
    font-family: 'Inconsolata Nerd Font Mono';
    src: url(data:font/truetype;base64,$regularBase64) format('truetype');
    font-weight: 1 999;
    font-style: normal;
}
            """.trim()
        }

        private fun readResourceBase64(path: String): String {
            val stream = CefTerminalPanel::class.java.getResourceAsStream(path)
            if (stream == null) {
                LOG.error("[AgentCLI] readResourceBase64: resource NOT FOUND at path '$path'")
                throw IllegalStateException("Resource not found: $path")
            }
            return stream.use { Base64.getEncoder().encodeToString(it.readBytes()) }
        }

        private fun readResource(path: String): String {
            val stream = CefTerminalPanel::class.java.getResourceAsStream(path)
            if (stream == null) {
                LOG.error("[AgentCLI] readResource: resource NOT FOUND at path '$path'")
                throw IllegalStateException("Resource not found: $path")
            }
            return stream.bufferedReader().use { it.readText() }
        }
    }
}
