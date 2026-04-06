package org.sajith.claudecode.plugin.terminal

/**
 * HTML shell for the embedded xterm.js page loaded in JCEF. Bundled script/CSS blobs and
 * [inputQueryJs] / [resizeQueryJs] are injected by [CefTerminalPanel].
 */
internal fun buildCefTerminalPageHtml(
    fontSize: Int,
    xtermCss: String,
    xtermJs: String,
    fitAddonJs: String,
    webLinksAddonJs: String,
    unicode11AddonJs: String,
    inputQueryJs: String,
    resizeQueryJs: String,
): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
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
</style>
<style>
$xtermCss
</style>
</head>
<body>
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
    console.log('[ClaudeCode] xterm.js init: starting');
    var Terminal = window.Terminal;
    var FitAddon = window.FitAddon;
    var WebLinksAddon = window.WebLinksAddon;
    var Unicode11Addon = window.Unicode11Addon;

    if (!Terminal) { console.error('[ClaudeCode] xterm.js init: Terminal class not found!'); return; }
    if (!FitAddon) { console.error('[ClaudeCode] xterm.js init: FitAddon not found!'); return; }

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
    console.log('[ClaudeCode] xterm.js init: terminal opened in DOM');

    var lastCols = 0;
    var lastRows = 0;

    // ── Window bridge functions called from Java ──────────────────

    // Write PTY output (Base64 encoded)
    window.writeTerminalData = function(base64Data) {
        try {
            var binary = atob(base64Data);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            term.write(bytes);
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

        console.log('[ClaudeCode] fitAndRestore PRE: scrollTop=' + scrollTop
            + ' scrollHeight=' + scrollHeight + ' clientHeight=' + clientHeight
            + ' atBottom=' + atBottom + ' ratio=' + ratio.toFixed(4)
            + ' isAlt=' + isAlt + ' cols=' + term.cols + ' rows=' + term.rows);

        // 2. Fit terminal to new container size
        fitAddon.fit();

        console.log('[ClaudeCode] fitAndRestore POST: cols=' + term.cols + ' rows=' + term.rows);

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
            var actualScrollTop = viewport.scrollTop;

            console.log('[ClaudeCode] fitAndRestore RESTORE: targetTop=' + targetTop
                + ' actualScrollTop=' + actualScrollTop
                + ' maxScroll=' + maxScroll + ' newScrollHeight=' + newScrollHeight
                + ' newClientHeight=' + newClientHeight
                + ' atBottom=' + atBottom + ' ratio=' + ratio.toFixed(4));
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

    // Intercept Shift+Enter: send newline so Claude Code CLI treats it
    // as a continuation line rather than submitting the input.
    var shiftEnterPending = false;
    term.attachCustomKeyEventHandler(function(e) {
        if (e.key === 'Enter' && e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
            if (e.type === 'keydown') {
                shiftEnterPending = true;
                var seq = '\x1b[13;2u';
                var base64 = btoa(seq);
                console.log('[ClaudeCode] Shift+Enter keydown intercepted, sending CSI u');
                $inputQueryJs
            }
            // Block both keydown AND keyup for Shift+Enter
            return false;
        }
        return true;
    });

    // Keyboard input: JS -> Java
    term.onData(function(data) {
        // If Shift+Enter was just handled, suppress the \r that xterm may still emit
        if (shiftEnterPending) {
            shiftEnterPending = false;
            var hex = [];
            for (var i = 0; i < data.length; i++) hex.push(data.charCodeAt(i).toString(16).padStart(2, '0'));
            console.log('[ClaudeCode] onData after Shift+Enter suppressed: hex=[' + hex.join(' ') + ']');
            return;
        }
        try {
            var base64 = btoa(data);
            $inputQueryJs
        } catch(e) {
            console.error('onData error:', e);
        }
    });

    // Binary input (for special keys)
    term.onBinary(function(data) {
        try {
            var base64 = btoa(data);
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
