package dev.evesharedmap.server.marker

import dev.evesharedmap.server.domain.FieldValidationException
import dev.evesharedmap.server.domain.TextValidation
import dev.evesharedmap.server.service.ServiceErrors
import dev.evesharedmap.server.universe.SolarSystemAllowlist

class SharedMarkerValidation(
    private val allowlist: SolarSystemAllowlist,
) {
    val universeBuild: String get() = allowlist.universeBuild

    fun systemId(value: Int): Int {
        if (value <= 0 || value !in allowlist) throw ServiceErrors.invalid("systemId", "UNKNOWN_SOLAR_SYSTEM")
        return value
    }

    fun fields(
        name: String,
        color: String,
        tags: List<String>,
        notes: String?,
    ): SharedMarkerFields = try {
        SharedMarkerFields(
            name = TextValidation.markerName(name),
            color = parseColor(color),
            tags = normalizeTags(tags),
            notes = TextValidation.markerNotes(notes),
        )
    } catch (error: FieldValidationException) {
        throw ServiceErrors.invalid(error.field, error.reason)
    }

    private fun parseColor(value: String): SharedMarkerColor = try {
        SharedMarkerColor.valueOf(value)
    } catch (_: IllegalArgumentException) {
        throw FieldValidationException("color", "UNKNOWN_COLOR")
    }

    private fun normalizeTags(values: List<String>): List<String> {
        if (values.size > MAX_TAGS) throw FieldValidationException("tags", "TOO_MANY")
        val normalized = ArrayList<String>(values.size)
        val unique = HashSet<String>(values.size)
        values.forEach { value ->
            if (!TAG_PATTERN.matches(value)) throw FieldValidationException("tags", "INVALID_TAG")
            if (!unique.add(value)) throw FieldValidationException("tags", "DUPLICATE_TAG")
            normalized += value
        }
        return normalized
    }

    private companion object {
        const val MAX_TAGS = 9
        val TAG_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}
