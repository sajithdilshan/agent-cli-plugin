package org.sajith.agentcli.plugin.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

class FileLinkTest {
    @Test
    fun `parses path and line as the mod encodes them`() {
        val link = FileLink.parse(URI("agentcli://open?path=%2Fp%2Fsrc%2FA+B.kt&line=3"))
        assertEquals(FileLink("/p/src/A B.kt", 3), link)
    }

    @Test
    fun `line is optional and must be positive`() {
        assertEquals(FileLink("/p/A.kt", null), FileLink.parse(URI("agentcli://open?path=%2Fp%2FA.kt")))
        assertEquals(FileLink("/p/A.kt", null), FileLink.parse(URI("agentcli://open?path=%2Fp%2FA.kt&line=0")))
    }

    @Test
    fun `rejects other schemes, actions and missing paths`() {
        assertNull(FileLink.parse(URI("https://open?path=%2Fp%2FA.kt")))
        assertNull(FileLink.parse(URI("agentcli://delete?path=%2Fp%2FA.kt")))
        assertNull(FileLink.parse(URI("agentcli://open?line=3")))
        assertNull(FileLink.parse(URI("agentcli://open")))
    }
}
