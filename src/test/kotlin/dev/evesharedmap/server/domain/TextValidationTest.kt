package dev.evesharedmap.server.domain

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextValidationTest {
    @Test
    fun `names are trimmed and normalized to NFC`() {
        val decomposed = "  Cafe\u0301  "
        val normalized = TextValidation.displayName(decomposed)

        assertEquals("Café", normalized)
        assertEquals(true, Normalizer.isNormalized(normalized, Normalizer.Form.NFC))
    }

    @Test
    fun `Unicode code points are counted rather than UTF-16 units`() {
        assertEquals("😀".repeat(80), TextValidation.workspaceName("😀".repeat(80)))
        assertFailsWith<FieldValidationException> { TextValidation.workspaceName("😀".repeat(81)) }
    }

    @Test
    fun `control format and line characters are rejected`() {
        listOf("bad\nname", "bad\tname", "bad\u0000name", "bad\u200Bname").forEach { value ->
            assertFailsWith<FieldValidationException>(value) { TextValidation.displayName(value) }
        }
    }
}
