package dev.evesharedmap.server.domain

import java.text.Normalizer

class FieldValidationException(
    val field: String,
    val reason: String,
) : IllegalArgumentException("Invalid $field: $reason")

object TextValidation {
    private const val MAX_NAME_CODE_POINTS = 80

    fun displayName(value: String): String = normalizedName(value, "displayName")

    fun workspaceName(value: String): String = normalizedName(value, "name")

    fun deviceName(value: String): String = normalizedName(value, "deviceName")

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
}
