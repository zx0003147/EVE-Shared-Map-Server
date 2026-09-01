package dev.evesharedmap.server.domain

import java.text.Normalizer

class FieldValidationException(
    val field: String,
    val reason: String,
) : IllegalArgumentException("Invalid $field: $reason")

object TextValidation {
    private const val MAX_NAME_CODE_POINTS = 80
    private const val MAX_NOTES_CODE_POINTS = 2_000

    fun displayName(value: String): String = normalizedName(value, "displayName")

    fun workspaceName(value: String): String = normalizedName(value, "name")

    fun deviceName(value: String): String = normalizedName(value, "deviceName")

    fun markerName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) throw FieldValidationException("name", "REQUIRED")
        if (normalized.codePointCount(0, normalized.length) > MAX_NAME_CODE_POINTS) {
            throw FieldValidationException("name", "TOO_LONG")
        }
        if (normalized.codePoints().anyMatch(::isRejectedMarkerNameCodePoint)) {
            throw FieldValidationException("name", "CONTROL_CHARACTER")
        }
        return normalized
    }

    fun markerNotes(value: String?): String? {
        if (value == null) return null
        val normalizedLineEndings = value.replace("\r\n", "\n")
        val normalized = Normalizer.normalize(normalizedLineEndings, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) return null
        if (normalized.codePointCount(0, normalized.length) > MAX_NOTES_CODE_POINTS) {
            throw FieldValidationException("notes", "TOO_LONG")
        }
        if (normalized.codePoints().anyMatch(::isRejectedMarkerNotesCodePoint)) {
            throw FieldValidationException("notes", "CONTROL_CHARACTER")
        }
        return normalized
    }

    private fun normalizedName(value: String, field: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) throw FieldValidationException(field, "REQUIRED")
        if (normalized.codePointCount(0, normalized.length) > MAX_NAME_CODE_POINTS) {
            throw FieldValidationException(field, "TOO_LONG")
        }
        if (normalized.codePoints().anyMatch(::isRejectedControl)) {
            throw FieldValidationException(field, "CONTROL_CHARACTER")
        }
        return normalized
    }

    private fun isRejectedControl(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        -> true
        else -> false
    }

    private fun isRejectedMarkerNameCodePoint(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) ||
            Character.getType(codePoint) == Character.LINE_SEPARATOR.toInt() ||
            Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR.toInt()

    private fun isRejectedMarkerNotesCodePoint(codePoint: Int): Boolean = when {
        codePoint == '\n'.code || codePoint == '\t'.code -> false
        Character.isISOControl(codePoint) -> true
        Character.getType(codePoint) == Character.LINE_SEPARATOR.toInt() -> true
        Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }
}
