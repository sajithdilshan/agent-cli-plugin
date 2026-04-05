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

    console.log('[ClaudeCode] xterm.js init: Terminal=' + (typeof Terminal) + ', FitAddon=' + (typeof FitAddon) + ', WebLinksAddon=' + (typeof WebLinksAddon) + ', Unicode11Addon=' + (typeof Unicode11Addon));

    if (!Terminal) { console.error('[ClaudeCode] xterm.js init: Terminal class not found!'); return; }
    if (!FitAddon) { console.error('[ClaudeCode] xterm.js init: FitAddon not found!'); return; }

    var term = new Terminal({
        cursorBlink: true,
        cursorStyle: 'bar',
        fontSize: $fontSize,
        fontFamily: 'Inconsolata Nerd Font Mono, JetBrainsMono Nerd Font Mono, JetBrainsMono Nerd Font, Hack Nerd Font Mono, FiraCode Nerd Font Mono, Symbols Nerd Font Mono, JetBrains Mono, Menlo, Monaco, Consolas, Courier New, monospace',
        allowProposedApi: true,
        scrollback: 10000,
        smoothScrollDuration: 100,
        macOptionIsMeta: true,
        drawBoldTextInBrightColors: true
    });
    console.log('[ClaudeCode] xterm.js init: Terminal instance created');

    var fitAddon = new FitAddon.FitAddon();
    var webLinksAddon = new WebLinksAddon.WebLinksAddon();
    term.loadAddon(fitAddon);
    term.loadAddon(webLinksAddon);
    if (Unicode11Addon) {
        var unicode11Addon = new Unicode11Addon.Unicode11Addon();
        term.loadAddon(unicode11Addon);
        term.unicode.activeVersion = '11';
        console.log('[ClaudeCode] xterm.js init: unicode11 addon loaded, activeVersion=' + term.unicode.activeVersion);
    }
    term.open(document.getElementById('terminal'));
    console.log('[ClaudeCode] xterm.js init: terminal opened in DOM');

    // Initial fit
    setTimeout(function() {
        fitAddon.fit();
        console.log('[ClaudeCode] xterm.js init: initial fit done, cols=' + term.cols + ' rows=' + term.rows);
    }, 50);

    // Called from Java to write PTY output (Base64 encoded)
    window.writeTerminalData = function(base64Data) {
        try {
            var binary = atob(base64Data);
            // Convert binary string to Uint8Array for proper UTF-8 handling
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            term.write(bytes);
        } catch(e) {
            console.error('writeTerminalData error:', e);
        }
    };

    // Called from Java to set theme
    window.setTerminalTheme = function(themeJson) {
        try {
            var theme = JSON.parse(themeJson);
            term.options.theme = theme;
            document.body.style.background = theme.background || '#1e1e1e';
            // Detect light vs dark for scrollbar styling
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

    // Called from Java to trigger resize
    window.fitTerminal = function() {
        doFit();
    };

    // Called from Java to focus terminal
    window.focusTerminal = function() {
        term.focus();
    };

    // Keyboard input: JS -> Java
    term.onData(function(data) {
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

    // Resize events: JS -> Java
    var resizeTimeout = null;
    var restoreTimeout = null;
    var lastCols = term.cols;
    var lastRows = term.rows;
    var isFitting = false;

    // Persistent scroll target that survives across rapid cascading resizes.
    // Only captured when viewportY is NOT 0-after-fit (i.e. a real user position).
    var scrollTarget = null; // null = not in a resize cascade, 'bottom' or a line number

    function doFit() {
        if (isFitting) return;

        var buf = term.buffer.active;
        var currentViewportY = buf.viewportY;
        var baseY = buf.baseY;
        var cursorY = buf.cursorY;
        var totalLines = baseY + cursorY;

        // Only capture scroll target at the START of a resize cascade,
        // not on subsequent resizes where viewportY has been reset to 0 by fit().
        if (scrollTarget === null) {
            var wasAtBottom = (currentViewportY + term.rows >= totalLines);
            if (wasAtBottom) {
                scrollTarget = 'bottom';
            } else {
                // Save as a ratio (0.0 to 1.0) so it survives buffer reflow changes
                scrollTarget = (baseY > 0) ? (currentViewportY / baseY) : 0;
            }
        }

        isFitting = true;
        fitAddon.fit();
        isFitting = false;

        // Cancel any pending restore — a new fit just happened, we'll restore fresh.
        clearTimeout(restoreTimeout);
        var capturedTarget = scrollTarget;
        restoreTimeout = setTimeout(function() {
            var viewport = document.querySelector('.xterm-viewport');
            if (!viewport) return;
            if (capturedTarget === 'bottom') {
                viewport.scrollTop = viewport.scrollHeight;
            } else {
                var newBaseY = term.buffer.active.baseY;
                var targetLine = Math.round(capturedTarget * newBaseY);
                var rowHeight = (newBaseY + term.rows > 0) ?
                    (viewport.scrollHeight / (newBaseY + term.rows)) : 15;
                viewport.scrollTop = targetLine * rowHeight;
            }
        }, 50);
    }

    var resizeObserver = new ResizeObserver(function() {
        if (isFitting) return;
        clearTimeout(resizeTimeout);
        resizeTimeout = setTimeout(function() {
            doFit();

            // Only notify Java if size actually changed
            if (term.cols !== lastCols || term.rows !== lastRows) {
                lastCols = term.cols;
                lastRows = term.rows;
                var size = JSON.stringify({ cols: term.cols, rows: term.rows });
                try {
                    $resizeQueryJs
                } catch(e) {
                    console.error('resize error:', e);
                }
            }
        }, 150);
    });
    resizeObserver.observe(document.getElementById('terminal'));

    // Clear the scroll target after the resize cascade settles
    var cascadeTimeout = null;
    term.onRender(function() {
        if (scrollTarget !== null) {
            clearTimeout(cascadeTimeout);
            cascadeTimeout = setTimeout(function() {
                scrollTarget = null;
            }, 300);
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
