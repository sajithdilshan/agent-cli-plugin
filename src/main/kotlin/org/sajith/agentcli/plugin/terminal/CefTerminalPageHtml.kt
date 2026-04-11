package org.sajith.agentcli.plugin.terminal

/**
 * HTML shell for the embedded xterm.js page loaded in JCEF. Bundled script/CSS blobs and
 * [inputQueryJs] / [resizeQueryJs] are injected by [CefTerminalPanel].
 */
internal fun buildCefTerminalPageHtml(
    fontSize: Int,
    fontFaceCss: String,
    xtermCss: String,
    xtermJs: String,
    fitAddonJs: String,
    webLinksAddonJs: String,
    unicode11AddonJs: String,
    inputQueryJs: String,
    resizeQueryJs: String,
    loadingText: String = "Starting Session...",
): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="UTF-8">
    <style>
    $fontFaceCss
    </style>
    <style>
    html, body {
        margin: 0;
        padding: 0;
        width: 100%;
        height: 100%;
        overflow: hidden;
        background: #1e1e1e;
    }
    #terminal {
        width: 100%;
        height: 100%;
    }
    .xterm {
        padding: 4px;
    }
    /* Dark-themed scrollbar */
    .xterm-viewport::-webkit-scrollbar {
        width: 10px;
    }
    .xterm-viewport::-webkit-scrollbar-track {
        background: transparent;
    }
    .xterm-viewport::-webkit-scrollbar-thumb {
        background: rgba(255, 255, 255, 0.2);
        border-radius: 5px;
    }
    .xterm-viewport::-webkit-scrollbar-thumb:hover {
        background: rgba(255, 255, 255, 0.35);
    }
    /* Light theme override — set via JS */
    body.light-theme .xterm-viewport::-webkit-scrollbar-thumb {
        background: rgba(0, 0, 0, 0.2);
    }
    body.light-theme .xterm-viewport::-webkit-scrollbar-thumb:hover {
        background: rgba(0, 0, 0, 0.35);
    }
    #loading-overlay {
        position: absolute;
        top: 0; left: 0; right: 0; bottom: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        background: #1e1e1e;
        z-index: 1000;
        transition: opacity 0.5s ease;
    }
    #loading-overlay.fade-out {
        opacity: 0;
        pointer-events: none;
    }
    .loading-content {
        text-align: center;
        color: rgba(255, 255, 255, 0.5);
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
        font-size: 13px;
        letter-spacing: 0.5px;
    }
    .loading-text {
        margin-top: 20px;
        animation: textPulse 2s ease-in-out infinite;
    }
    /* ── Animated robot logo ── */
    .robot {
        width: 64px;
        height: 64px;
        margin: 0 auto;
        position: relative;
    }
    .robot-head {
        position: absolute;
        top: 18px; left: 6px;
        width: 52px; height: 38px;
        border: 2px solid rgba(255,255,255,0.4);
        border-radius: 10px;
        animation: headBob 2s ease-in-out infinite;
    }
    .robot-antenna {
        position: absolute;
        top: 4px; left: 50%;
        width: 2px; height: 14px;
        background: rgba(255,255,255,0.4);
        transform: translateX(-50%);
        animation: headBob 2s ease-in-out infinite;
    }
    .robot-antenna::after {
        content: '';
        position: absolute;
        top: -4px; left: 50%;
        width: 6px; height: 6px;
        border: 2px solid rgba(255,255,255,0.4);
        border-radius: 50%;
        transform: translateX(-50%);
        animation: antennaPulse 1.5s ease-in-out infinite;
    }
    .robot-eye {
        position: absolute;
        top: 30px;
        width: 10px; height: 10px;
        border: 2px solid rgba(255,255,255,0.4);
        border-radius: 3px;
        animation: headBob 2s ease-in-out infinite;
    }
    .robot-eye-left { left: 16px; }
    .robot-eye-right { right: 16px; }
    .robot-eye::after {
        content: '';
        position: absolute;
        top: 2px; left: 2px;
        width: 4px; height: 4px;
        background: rgba(255,255,255,0.5);
        border-radius: 1px;
        animation: blink 3s ease-in-out infinite;
    }
    .robot-mouth {
        position: absolute;
        top: 46px; left: 50%;
        width: 18px; height: 2px;
        background: rgba(255,255,255,0.4);
        transform: translateX(-50%);
        animation: headBob 2s ease-in-out infinite;
    }
    .robot-ear {
        position: absolute;
        top: 32px;
        width: 6px; height: 14px;
        border: 2px solid rgba(255,255,255,0.4);
        border-radius: 3px;
        animation: headBob 2s ease-in-out infinite;
    }
    .robot-ear-left { left: 0; }
    .robot-ear-right { right: 0; }
    @keyframes headBob {
        0%, 100% { transform: translateY(0); }
        50% { transform: translateY(-3px); }
    }
    @keyframes antennaPulse {
        0%, 100% { box-shadow: 0 0 0 0 rgba(255,255,255,0); border-color: rgba(255,255,255,0.4); }
        50% { box-shadow: 0 0 6px 2px rgba(255,255,255,0.2); border-color: rgba(255,255,255,0.7); }
    }
    @keyframes blink {
        0%, 42%, 48%, 100% { opacity: 1; }
        45% { opacity: 0; }
    }
    @keyframes textPulse {
        0%, 100% { opacity: 0.5; }
        50% { opacity: 0.9; }
    }
    </style>
    <style>
    $xtermCss
    </style>
    </head>
    <body>
    <div id="loading-overlay">
        <div class="loading-content">
            <div class="robot">
                <div class="robot-antenna"></div>
                <div class="robot-head"></div>
                <div class="robot-eye robot-eye-left"></div>
                <div class="robot-eye robot-eye-right"></div>
                <div class="robot-mouth"></div>
                <div class="robot-ear robot-ear-left"></div>
                <div class="robot-ear robot-ear-right"></div>
            </div>
            <div class="loading-text">$loadingText</div>
        </div>
    </div>
    <div id="terminal"></div>
    <script>
    $xtermJs
    </script>
    <script>
    $fitAddonJs
    </script>
    <script>
    $webLinksAddonJs
    </script>
    <script>
    $unicode11AddonJs
    </script>
    <script>
    (function() {
        var Terminal = window.Terminal;
        var FitAddon = window.FitAddon;
        var WebLinksAddon = window.WebLinksAddon;
        var Unicode11Addon = window.Unicode11Addon;

        if (!Terminal) { console.error('Terminal class not found'); return; }
        if (!FitAddon) { console.error('FitAddon not found'); return; }

        var term = new Terminal({
            cursorBlink: true,
            cursorStyle: 'bar',
            fontSize: $fontSize,
            fontFamily: 'Inconsolata Nerd Font Mono, JetBrainsMono Nerd Font Mono, JetBrainsMono Nerd Font, Hack Nerd Font Mono, FiraCode Nerd Font Mono, Symbols Nerd Font Mono, JetBrains Mono, Menlo, Monaco, Consolas, Courier New, monospace',
            allowProposedApi: true,
            scrollback: 10000,
            macOptionIsMeta: true,
            drawBoldTextInBrightColors: true
        });

        var fitAddon = new FitAddon.FitAddon();
        var webLinksAddon = new WebLinksAddon.WebLinksAddon();
        term.loadAddon(fitAddon);
        term.loadAddon(webLinksAddon);
        if (Unicode11Addon) {
            var unicode11Addon = new Unicode11Addon.Unicode11Addon();
            term.loadAddon(unicode11Addon);
            term.unicode.activeVersion = '11';
        }
        term.open(document.getElementById('terminal'));

        // Wait for custom font to load before fitting, otherwise xterm
        // measures with a fallback font (wider chars) and calculates
        // too few columns, leaving a blank strip on the right.
        var fontReadyFit = function() {
            // Force xterm to re-measure character cells with the loaded font
            var currentFont = term.options.fontFamily;
            term.options.fontFamily = 'monospace';
            term.options.fontFamily = currentFont;

            // Wait two frames for the browser to render with the new font
            requestAnimationFrame(function() {
                requestAnimationFrame(function() {
                    window.fitAndRestore();
                });
            });
        };

        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(fontReadyFit);
        } else {
            setTimeout(fontReadyFit, 1000);
        }

        var lastCols = 0;
        var lastRows = 0;
        // After a resize, Claude Code redraws the screen (CSI 2J + 3J + full repaint).
        // We detect the redraw is done by debouncing: each PTY write resets a timer,
        // and when no more writes arrive for 150ms the redraw is considered settled.
        var postResizeScrollDeadline = 0;  // timestamp: ignore writes after this
        var postResizeSettleTimer = null;   // debounce timer for write-settle detection

        // ── Window bridge functions called from Java ──────────────────

        // Dismiss loading overlay: wait 2s, then check every 500ms
        // if output has gone quiet. Optimized for Claude Code which
        // outputs its banner then goes idle when ready for input.
        var overlayDismissed = false;
        var lastOutputTime = 0;

        function dismissOverlay() {
            if (overlayDismissed) return;
            overlayDismissed = true;
            var overlay = document.getElementById('loading-overlay');
            if (overlay) {
                overlay.classList.add('fade-out');
                setTimeout(function() { overlay.remove(); }, 400);
            }
        }

        setTimeout(function() {
            var check = setInterval(function() {
                if (overlayDismissed) { clearInterval(check); return; }
                if (!lastOutputTime || (Date.now() - lastOutputTime >= 500)) {
                    clearInterval(check);
                    dismissOverlay();
                }
            }, 500);
        }, 2000);

        // Write PTY output (Base64 encoded)
        window.writeTerminalData = function(base64Data) {
            try {
                var binary = atob(base64Data);
                var bytes = new Uint8Array(binary.length);
                for (var i = 0; i < binary.length; i++) {
                    bytes[i] = binary.charCodeAt(i);
                }
                term.write(bytes, function() {
                    lastOutputTime = Date.now();
                    if (Date.now() < postResizeScrollDeadline) {
                        if (postResizeSettleTimer) { clearTimeout(postResizeSettleTimer); }
                        postResizeSettleTimer = setTimeout(function() {
                            postResizeSettleTimer = null;
                            term.scrollToBottom();
                        }, 150);
                    }
                });
            } catch(e) {
                console.error('writeTerminalData error:', e);
            }
        };

        // Set theme
        window.setTerminalTheme = function(themeJson) {
            try {
                var theme = JSON.parse(themeJson);
                term.options.theme = theme;
                document.body.style.background = theme.background || '#1e1e1e';
                var bg = theme.background || '#1e1e1e';
                var r = parseInt(bg.slice(1,3), 16) || 0;
                var g = parseInt(bg.slice(3,5), 16) || 0;
                var b = parseInt(bg.slice(5,7), 16) || 0;
                var luminance = (0.299 * r + 0.587 * g + 0.114 * b);
                if (luminance > 128) {
                    document.body.classList.add('light-theme');
                } else {
                    document.body.classList.remove('light-theme');
                }
            } catch(e) {
                console.error('setTerminalTheme error:', e);
            }
        };

        // Focus terminal
        window.focusTerminal = function() {
            term.focus();
        };

        // ── Fit + scroll restore (called from Java after resize settles) ──
        //
        // Called ONCE by Java after the Swing resize gesture has settled (debounced).
        // No ResizeObserver, no cascading fits, no race conditions.
        // Steps: snapshot scroll → fit → restore scroll → notify Java of new size.
        //
        window.fitAndRestore = function() {
            var viewport = document.querySelector('.xterm-viewport');
            var isAlt = (term.buffer.active.type !== 'normal');

            // 1. Snapshot scroll position from the DOM BEFORE fit
            var scrollTop = 0;
            var scrollHeight = 1;
            var clientHeight = 1;
            var atBottom = true;
            var ratio = 1.0;

            if (viewport && !isAlt) {
                scrollTop = viewport.scrollTop;
                scrollHeight = viewport.scrollHeight;
                clientHeight = viewport.clientHeight;
                atBottom = (scrollTop + clientHeight >= scrollHeight - 2);
                ratio = (scrollHeight > 0) ? (scrollTop / scrollHeight) : 0;
            }

            // 2. Fit terminal to new container size
            fitAddon.fit();

            // 3. Restore scroll position
            if (viewport && !isAlt) {
                var newScrollHeight = viewport.scrollHeight;
                var newClientHeight = viewport.clientHeight;
                var maxScroll = newScrollHeight - newClientHeight;
                var targetTop;

                if (atBottom) {
                    targetTop = maxScroll;
                } else {
                    targetTop = Math.round(ratio * newScrollHeight);
                    targetTop = Math.max(0, Math.min(targetTop, maxScroll));
                }

                viewport.scrollTop = targetTop;

                // Claude Code clears screen + scrollback after resize (CSI 2J + 3J),
                // destroying scroll state. Detect when redraw is done by debouncing
                // PTY writes — when no more arrive for 150ms, scrollToBottom once.
                postResizeScrollDeadline = Date.now() + 3000;
                if (postResizeSettleTimer) { clearTimeout(postResizeSettleTimer); postResizeSettleTimer = null; }
            }

            // 4. Notify Java of new terminal dimensions if changed
            if (term.cols !== lastCols || term.rows !== lastRows) {
                lastCols = term.cols;
                lastRows = term.rows;
                var size = JSON.stringify({ cols: term.cols, rows: term.rows });
                try {
                    $resizeQueryJs
                } catch(e) {
                    console.error('resize notify error:', e);
                }
            }
        };

        // ── Keyboard handling ──────────────────────────────────────────

        // Encode a string to base64, handling Unicode correctly.
        // btoa() only supports Latin-1; we first encode to UTF-8 bytes
        // so that pasted text containing emoji, CJK, etc. does not throw.
        function toBase64(str) {
            var bytes = new TextEncoder().encode(str);
            var binary = '';
            for (var i = 0; i < bytes.length; i++) {
                binary += String.fromCharCode(bytes[i]);
            }
            return btoa(binary);
        }

        // Intercept Shift+Enter: send newline so Claude Code CLI treats it
        // as a continuation line rather than submitting the input.
        // Some environments still emit a trailing Enter data event even when Shift+Enter
        // is intercepted. Only suppress CR/LF for a very short window.
        var suppressShiftEnterDataUntilMs = 0;
        term.attachCustomKeyEventHandler(function(e) {
            if (e.key === 'Enter' && e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
                if (e.type === 'keydown') {
                    suppressShiftEnterDataUntilMs = Date.now() + 250;
                    var seq = '\x1b[13;2u';
                    var base64 = btoa(seq);
                    $inputQueryJs
                }
                // Block both keydown AND keyup for Shift+Enter
                return false;
            }
            return true;
        });

        // Keyboard input: JS -> Java
        term.onData(function(data) {
            if (suppressShiftEnterDataUntilMs > 0) {
                if (Date.now() > suppressShiftEnterDataUntilMs) {
                    suppressShiftEnterDataUntilMs = 0;
                } else if (data === '\r' || data === '\n' || data === '\r\n') {
                    suppressShiftEnterDataUntilMs = 0;
                    return;
                }
            }
            try {
                var base64 = toBase64(data);
                $inputQueryJs
            } catch(e) {
                console.error('onData error:', e);
            }
        });

        // Binary input (for special keys)
        term.onBinary(function(data) {
            try {
                var base64 = toBase64(data);
                $inputQueryJs
            } catch(e) {
                console.error('onBinary error:', e);
            }
        });

        // Focus terminal when clicked
        document.addEventListener('click', function() {
            term.focus();
        });
    })();
    </script>
    </body>
    </html>
    """.trimIndent()
