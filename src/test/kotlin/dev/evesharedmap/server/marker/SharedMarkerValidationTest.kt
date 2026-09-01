package dev.evesharedmap.server.marker

import dev.evesharedmap.server.service.ServiceException
import dev.evesharedmap.server.universe.SolarSystemAllowlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SharedMarkerValidationTest {
    private val validation = SharedMarkerValidation(SolarSystemAllowlist.load())

    @Test
    fun `valid fields normalize NFC whitespace line endings and preserve tag order`() {
        val fields = validation.fields(
            name = "  Cafe\u0301 staging  ",
            color = "BLUE",
            tags = listOf("strategic", "custom.tag"),
            notes = "  first\r\nsecond  ",
        )

        assertEquals("Café staging", fields.name)
        assertEquals(SharedMarkerColor.BLUE, fields.color)
        assertEquals(listOf("strategic", "custom.tag"), fields.tags)
        assertEquals("first\nsecond", fields.notes)
        assertNull(validation.fields("Name", "WHITE", emptyList(), " \t\r\n ").notes)
    }

    @Test
    fun `invalid marker fields use frozen domain errors`() {
        val cases = listOf<() -> Unit>(
            { validation.systemId(0) },
            { validation.systemId(Int.MAX_VALUE) },
            { validation.fields(" ", "BLUE", emptyList(), null) },
            { validation.fields("Name", "#ffffff", emptyList(), null) },
            { validation.fields("Name", "BLUE", listOf("danger", "danger"), null) },
            { validation.fields("Name", "BLUE", listOf("Unknown Tag"), null) },
            { validation.fields("Name", "BLUE", List(10) { "tag$it" }, null) },
            { validation.fields("Name\nBreak", "BLUE", emptyList(), null) },
            { validation.fields("Name", "BLUE", emptyList(), "bad\u0000notes") },
        )

        cases.forEach { operation ->
            val error = assertFailsWith<ServiceException> { operation() }
            assertEquals("INVALID_ARGUMENT", error.code)
            assertEquals(422, error.status)
        }
    }
}
