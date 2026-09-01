package dev.evesharedmap.server.universe

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolarSystemAllowlistTest {
    @Test
    fun `packaged allowlist has frozen provenance and known security-space systems`() {
        val allowlist = SolarSystemAllowlist.load()

        assertEquals("sde-3466501", allowlist.universeBuild)
        assertEquals(8_490, allowlist.size)
        assertTrue(30000142 in allowlist) // Jita, highsec
        assertTrue(30002537 in allowlist) // Amamake, lowsec
        assertTrue(30004759 in allowlist) // 1DQ1-A, nullsec
        assertFalse(0 in allowlist)
        assertFalse(-1 in allowlist)
        assertFalse(Int.MAX_VALUE in allowlist)
    }

    @Test
    fun `loader fails closed for missing metadata duplicates and count mismatch`() {
        listOf(
            "",
            resource(ids = listOf(30000001, 30000001), count = 2),
            resource(ids = listOf(30000001), count = 2),
            resource(ids = emptyList(), count = 0),
        ).forEach { invalid ->
            assertFailsWith<IllegalStateException> {
                SolarSystemAllowlist.parse(ByteArrayInputStream(invalid.toByteArray()))
            }
        }
    }

    private fun resource(ids: List<Int>, count: Int): String = buildString {
        appendLine("format=1")
        appendLine("universeBuild=sde-test")
        appendLine("source=test")
        appendLine("sourceSha256=${"a".repeat(64)}")
        appendLine("systemCount=$count")
        appendLine()
        ids.forEach { appendLine(it) }
    }
}
