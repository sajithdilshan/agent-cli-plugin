package org.sajith.agentcli.plugin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ClaudeCodeHistoryReaderTest {
    @TempDir
    lateinit var dir: File

    private fun user(
        content: String,
        meta: Boolean = false,
    ): String {
        val metaField = if (meta) "\"isMeta\":true," else ""
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{$metaField\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"$escaped\"}}"
    }

    private fun parse(vararg lines: String): String {
        val file = File(dir, "s.jsonl").apply { writeText(lines.joinToString("\n")) }
        return ClaudeCodeHistoryReader.parseSessionFile(file).firstMessage
    }

    @Test
    fun `skips slash commands and caveats to find the real prompt`() {
        val msg =
            parse(
                user("<local-command-caveat>Caveat text</local-command-caveat>", meta = true),
                user("<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args></command-args>"),
                user("<local-command-stdout>Set model</local-command-stdout>"),
                user("fix the login bug"),
            )
        assertEquals("fix the login bug", msg)
    }

    @Test
    fun `falls back to the command when no real prompt exists`() {
        val msg =
            parse(
                user("<command-name>/review</command-name>\n<command-args>123</command-args>"),
                user("<local-command-stdout>done</local-command-stdout>"),
            )
        assertEquals("/review 123", msg)
    }

    @Test
    fun `plain first message is used as-is`() {
        assertEquals("hello", parse(user("hello")))
    }
}
