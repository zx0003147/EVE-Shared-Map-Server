package dev.evesharedmap.server.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class ServiceException(
    val status: Int,
    val code: String,
    val safeMessage: String,
    val details: JsonObject? = null,
) : RuntimeException(safeMessage)

object ServiceErrors {
    fun invalid(field: String, reason: String): ServiceException = ServiceException(
        status = 422,
        code = "INVALID_ARGUMENT",
        safeMessage = "One or more fields are invalid.",
        details = kotlinx.serialization.json.buildJsonObject {
            put(
                "fieldErrors",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("field", field)
                            put("reason", reason)
                        },
                    )
                },
            )
        },
    )

    fun unauthenticated(): ServiceException = ServiceException(
        401,
        "UNAUTHENTICATED",
        "A valid device credential is required.",
    )

    fun tokenRevoked(): ServiceException = ServiceException(
        401,
        "TOKEN_REVOKED",
        "The device credential is no longer valid.",
    )

    fun tokenExpired(): ServiceException = ServiceException(
        401,
        "TOKEN_EXPIRED",
        "The device credential has expired.",
    )

    fun forbidden(message: String = "The current membership does not permit this operation."): ServiceException =
        ServiceException(403, "FORBIDDEN", message)

    fun notFound(): ServiceException = ServiceException(
        404,
        "NOT_FOUND",
        "The requested resource was not found.",
    )

    fun inviteInvalid(): ServiceException = ServiceException(
        401,
        "INVITE_INVALID",
        "The invite credential is not valid.",
    )

    fun inviteExpired(): ServiceException = ServiceException(
        401,
        "INVITE_EXPIRED",
        "The invite credential has expired.",
    )

    fun inviteRevoked(): ServiceException = ServiceException(
        401,
        "INVITE_REVOKED",
        "The invite credential was revoked.",
    )

    fun inviteUsed(): ServiceException = ServiceException(
        409,
        "INVITE_ALREADY_USED",
        "The invite credential was already exchanged.",
    )

    fun lastAdmin(): ServiceException = ServiceException(
        409,
        "LAST_ADMIN_REQUIRED",
        "The Workspace must retain at least one active Admin.",
    )

    fun memberVersionConflict(): ServiceException = ServiceException(
        409,
        "MEMBER_VERSION_CONFLICT",
        "The membership was changed by another administrator.",
    )
}
