package org.sajith.agentcli.plugin.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
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
import java.util.Base64
import javax.swing.JComponent
import javax.swing.Timer

class CefTerminalPanel(
    parentDisposable: Disposable,
    private val onInput: (String) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit,
    private val loadingText: String = "Starting Session...",
) : Disposable {
    private val browser: JBCefBrowser = JBCefBrowser()
    private val inputQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val resizeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var isPageLoaded = false
    private val pendingWrites = mutableListOf<String>()

    @Volatile
    private var pendingFocus = false

    @Volatile
    private var resizeEnabledState = false

    /** Swing timer to debounce componentResized events. */
    private val resizeDebounceTimer = Timer(200) { onResizeSettled() }.apply { isRepeats = false }

    val component: JComponent get() = browser.component

    init {

        inputQuery.addHandler { base64Data ->
            try {
                val decoded = String(Base64.getDecoder().decode(base64Data))
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
        browser.component.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (!resizeEnabledState || !isPageLoaded) return
                    resizeDebounceTimer.restart()
                }
            },
        )

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
            loadingText = loadingText,
        )
    }

    fun writeToTerminal(data: ByteArray) {
        val base64 = Base64.getEncoder().encodeToString(data)
        if (isPageLoaded) {
            executeJs("window.writeTerminalData('$base64')")
        } else {
            synchronized(pendingWrites) {
                pendingWrites.add(base64)
            }
        }
    }

    fun applyTheme() {
        val themeJson = TerminalThemeProvider.getThemeJson()
        val escaped = themeJson.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        executeJs("window.setTerminalTheme('$escaped')")
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
        val writes: List<String>
        synchronized(pendingWrites) {
            writes = pendingWrites.toList()
            pendingWrites.clear()
        }
        for (base64 in writes) {
            executeJs("window.writeTerminalData('$base64')")
        }
    }

    private fun executeJs(code: String) {
        browser.cefBrowser.executeJavaScript(code, browser.cefBrowser.url ?: "about:blank", 0)
    }

    override fun dispose() {
        resizeDebounceTimer.stop()
        Disposer.dispose(inputQuery)
        Disposer.dispose(resizeQuery)
        Disposer.dispose(browser)
    }

    companion object {
        private val LOG = Logger.getInstance(CefTerminalPanel::class.java)

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
