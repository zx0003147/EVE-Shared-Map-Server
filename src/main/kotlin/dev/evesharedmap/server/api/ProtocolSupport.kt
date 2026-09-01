package dev.evesharedmap.server.api

import dev.evesharedmap.server.service.ServiceException
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.security.MessageDigest
import java.util.UUID

internal val PROTOCOL_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

internal data class StrictBody<T>(
    val value: T,
    val json: JsonElement,
)

internal suspend inline fun <reified T> ApplicationCall.receiveStrictJson(
    maximumBytes: Int = 32 * 1024,
): StrictBody<T> {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        throw ServiceException(400, "INVALID_ARGUMENT", "Content-Type must be application/json.")
    }
    val raw = receiveText()
    if (raw.toByteArray(Charsets.UTF_8).size > maximumBytes) {
        throw ServiceException(413, "PAYLOAD_TOO_LARGE", "The request body is too large.")
    }
    try {
        val element = PROTOCOL_JSON.parseToJsonElement(raw)
        return StrictBody(PROTOCOL_JSON.decodeFromJsonElement(serializer<T>(), element), element)
    } catch (_: SerializationException) {
        throw ServiceException(400, "INVALID_ARGUMENT", "The request body is malformed.")
    } catch (_: IllegalArgumentException) {
        throw ServiceException(400, "INVALID_ARGUMENT", "The request body is malformed.")
    }
}

internal fun ApplicationCall.requireIdempotencyKey(): UUID {
    val raw = request.headers["Idempotency-Key"]
        ?: throw ServiceException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.")
    val parsed = try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw ServiceException(400, "INVALID_ARGUMENT", "Idempotency-Key must be a canonical UUID.")
    }
    if (parsed.toString() != raw) {
        throw ServiceException(400, "INVALID_ARGUMENT", "Idempotency-Key must be a canonical UUID.")
    }
    return parsed
}

internal fun canonicalUuid(raw: String?): UUID {
    val parsed = try {
        UUID.fromString(raw)
    } catch (_: Exception) {
        throw ServiceException(404, "NOT_FOUND", "The requested resource was not found.")
    }
    if (parsed.toString() != raw) {
        throw ServiceException(404, "NOT_FOUND", "The requested resource was not found.")
    }
    return parsed
}

internal fun mutationFingerprint(
    method: String,
    normalizedRoute: String,
    body: JsonElement = JsonNull,
    versionQuery: Long? = null,
): ByteArray {
    val canonical = canonicalize(body).toString()
    val material = buildString {
        append(method)
        append('\n')
        append(normalizedRoute)
        append('\n')
        append(canonical)
        append('\n')
        if (versionQuery != null) append(versionQuery)
    }.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(material)
    } finally {
        material.fill(0)
    }
}

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}
