package org.sajith.claudecode.plugin.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.sajith.claudecode.plugin.settings.ClaudeCodeSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Base64
import javax.swing.JComponent
import javax.swing.Timer

class CefTerminalPanel(
    parentDisposable: Disposable,
    private val onInput: (String) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit
) : Disposable {

    private val browser: JBCefBrowser = JBCefBrowser()
    private val inputQuery: JBCefJSQuery
    private val resizeQuery: JBCefJSQuery

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
        inputQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        resizeQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

        inputQuery.addHandler { base64Data ->
            try {
                val decoded = String(Base64.getDecoder().decode(base64Data))
                onInput(decoded)
            } catch (e: Exception) {
                LOG.warn("[ClaudeCode] CefTerminalPanel: failed to decode input from JS", e)
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
                    LOG.warn("[ClaudeCode] CefTerminalPanel: invalid resize data: $sizeJson")
                }
            } catch (e: Exception) {
                LOG.warn("[ClaudeCode] CefTerminalPanel: failed to parse resize from JS: $sizeJson", e)
            }
            JBCefJSQuery.Response("")
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    isPageLoaded = true
                    applyTheme()
                    flushPendingWrites()
                    if (pendingFocus) {
                        pendingFocus = false
                        executeJsFocusTerminal()
                    }
                    // Do initial fit after page load
                    executeJs("window.fitAndRestore()")
                }
            }

            override fun onLoadError(
                browser: CefBrowser, frame: CefFrame,
                errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
                errorText: String, failedUrl: String
            ) {
                LOG.error("[ClaudeCode] CefTerminalPanel: page load error: code=$errorCode text='$errorText' url='$failedUrl'")
            }
        }, browser.cefBrowser)

        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser, level: org.cef.CefSettings.LogSeverity,
                message: String, source: String, line: Int
            ): Boolean {
                when (level) {
                    org.cef.CefSettings.LogSeverity.LOGSEVERITY_ERROR,
                    org.cef.CefSettings.LogSeverity.LOGSEVERITY_FATAL ->
                        LOG.error("[ClaudeCode] JS: $message ($source:$line)")
                    org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING ->
                        LOG.warn("[ClaudeCode] JS: $message ($source:$line)")
                    else -> {
                        if (message.startsWith("[ClaudeCode]")) {
                            LOG.info("[ClaudeCode] JS: $message")
                        }
                    }
                }
                return false
            }
        }, browser.cefBrowser)

        // Listen to Swing component resize — this fires when the IDE resizes the tool window.
        // We debounce and only call JS once the resize gesture settles.
        browser.component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val size = e.component.size
                LOG.info("[ClaudeCode] componentResized: ${size.width}x${size.height} enabled=$resizeEnabledState loaded=$isPageLoaded")
                if (!resizeEnabledState || !isPageLoaded) return
                resizeDebounceTimer.restart()
            }
        })

        val html = buildTerminalHtml()
        browser.loadHTML(html)

        Disposer.register(parentDisposable, this)
    }

    private fun onResizeSettled() {
        if (!isPageLoaded || !resizeEnabledState) return
        LOG.info("[ClaudeCode] Swing resize settled, calling fitAndRestore")
        executeJs("window.fitAndRestore()")
    }

    private fun buildTerminalHtml(): String {
        val fontSize = ClaudeCodeSettings.getInstance().terminalFontSize
        val xtermJs = readResource("/terminal/xterm.js")
        val xtermCss = readResource("/terminal/xterm.css")
        val fitAddonJs = readResource("/terminal/xterm-addon-fit.js")
        val webLinksAddonJs = readResource("/terminal/xterm-addon-web-links.js")
        val unicode11AddonJs = readResource("/terminal/xterm-addon-unicode11.js")

        val inputQueryJs = inputQuery.inject("base64")
        val resizeQueryJs = resizeQuery.inject("size")

        return buildCefTerminalPageHtml(
            fontSize = fontSize,
            xtermCss = xtermCss,
            xtermJs = xtermJs,
            fitAddonJs = fitAddonJs,
            webLinksAddonJs = webLinksAddonJs,
            unicode11AddonJs = unicode11AddonJs,
            inputQueryJs = inputQueryJs,
            resizeQueryJs = resizeQueryJs,
        )
    }

    private fun readResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        if (stream == null) {
            LOG.error("[ClaudeCode] readResource: resource NOT FOUND at path '$path'")
            throw IllegalStateException("Resource not found: $path")
        }
        return stream.bufferedReader().readText()
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

        private val RESIZE_COLS_REGEX = Regex(""""cols"\s*:\s*(\d+)""")
        private val RESIZE_ROWS_REGEX = Regex(""""rows"\s*:\s*(\d+)""")

        private fun parseResizeDimensions(sizeJson: String): Pair<Int, Int>? {
            val cols = RESIZE_COLS_REGEX.find(sizeJson)?.groupValues?.get(1)?.toInt()
            val rows = RESIZE_ROWS_REGEX.find(sizeJson)?.groupValues?.get(1)?.toInt()
            return if (cols != null && rows != null && cols > 0 && rows > 0) cols to rows else null
        }
    }
}
