package org.sajith.claudecode.plugin.terminal

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Color

object TerminalThemeProvider {

    fun getThemeJson(): String {
        val isDark = LafManager.getInstance().currentUIThemeLookAndFeel?.isDark ?: true
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground

        return if (isDark) darkThemeJson(bg, fg) else lightThemeJson(bg, fg)
    }

    private fun hex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    private fun darkThemeJson(bg: Color, fg: Color): String {
        val b = hex(bg)
        val f = hex(fg)
        return """
        {
            "background": "$b",
            "foreground": "$f",
            "cursor": "$f",
            "cursorAccent": "$b",
            "selectionBackground": "rgba(255, 255, 255, 0.2)",
            "selectionForeground": null,
            "black": "#000000",
            "red": "#e06c75",
            "green": "#98c379",
            "yellow": "#e5c07b",
            "blue": "#61afef",
            "magenta": "#c678dd",
            "cyan": "#56b6c2",
            "white": "#abb2bf",
            "brightBlack": "#5c6370",
            "brightRed": "#e06c75",
            "brightGreen": "#98c379",
            "brightYellow": "#e5c07b",
            "brightBlue": "#61afef",
            "brightMagenta": "#c678dd",
            "brightCyan": "#56b6c2",
            "brightWhite": "#ffffff"
        }
        """.trimIndent()
    }

    private fun lightThemeJson(bg: Color, fg: Color): String {
        val b = hex(bg)
        val f = hex(fg)
        return """
        {
            "background": "$b",
            "foreground": "$f",
            "cursor": "$f",
            "cursorAccent": "$b",
            "selectionBackground": "rgba(0, 0, 0, 0.15)",
            "selectionForeground": null,
            "black": "#000000",
            "red": "#e45649",
            "green": "#50a14f",
            "yellow": "#c18401",
            "blue": "#4078f2",
            "magenta": "#a626a4",
            "cyan": "#0184bc",
            "white": "#a0a1a7",
            "brightBlack": "#383a42",
            "brightRed": "#e45649",
            "brightGreen": "#50a14f",
            "brightYellow": "#c18401",
            "brightBlue": "#4078f2",
            "brightMagenta": "#a626a4",
            "brightCyan": "#0184bc",
            "brightWhite": "#ffffff"
        }
        """.trimIndent()
    }
}
