package dev.evesharedmap.server.api

import dev.evesharedmap.server.domain.AuthenticationPrincipal
import dev.evesharedmap.server.domain.WorkspaceCapability
import dev.evesharedmap.server.domain.WorkspaceRole
import dev.evesharedmap.server.marker.SharedMarkerService
import dev.evesharedmap.server.route.RouteHandoffService
import dev.evesharedmap.server.security.RateLimiter
import dev.evesharedmap.server.security.RateLimits
import dev.evesharedmap.server.service.AuthorizationService
import dev.evesharedmap.server.service.IdempotentMutationResult
import dev.evesharedmap.server.service.MutationResponse
import dev.evesharedmap.server.service.ServiceException
import dev.evesharedmap.server.service.SharedMapService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.UUID

fun Route.sharedMapRoutes(
    service: SharedMapService,
    markerService: SharedMarkerService? = null,
    routeHandoffService: RouteHandoffService? = null,
    authorization: AuthorizationService = AuthorizationService(),
    rateLimiter: RateLimiter,
) {
    post("/api/v1/auth/exchange-invite") {
        call.enforceInviteExchangeRate(rateLimiter)
        val request = call.receiveStrictJson<ExchangeInviteRequest>(maximumBytes = 4 * 1024).value
        val issued = service.exchangeInvite(request.inviteToken, request.deviceName, call.requestId())
        call.respond(
            HttpStatusCode.Created,
            ExchangeInviteResponse(
                accessToken = issued.rawSecret,
                tokenId = issued.principal.tokenId.toString(),
                expiresAt = issued.principal.device.expiresAt.toString(),
                user = UserDto(
                    issued.principal.membership.user.userId.toString(),
                    issued.principal.membership.user.displayName,
                ),
                workspace = issued.principal.membership.toDto(),
            ),
        )
    }

    get("/api/v1/me") {
        val principal = call.authenticate(service)
        call.enforceAuthenticatedReadRate(rateLimiter, principal)
        call.respond(principal.toMeResponse())
    }

    get("/api/v1/me/devices") {
        val principal = call.authenticate(service)
        call.enforceAuthenticatedReadRate(rateLimiter, principal)
        call.respond(DevicesResponse(service.listDevices(principal.membership.memberId).map { it.toDto() }))
    }

    delete("/api/v1/me/devices/{tokenId}") {
        val principal = call.authenticate(service)
        call.enforceAdminWriteRate(rateLimiter, principal)
        val tokenId = canonicalUuid(call.parameters["tokenId"])
        call.executeMutation(
            service,
            principal,
        call.requireIdempotencyKey(),
        mutationFingerprint("DELETE", "/api/v1/me/devices/{tokenId}"),
        requiredCapability = WorkspaceCapability.READ,
        ) { connection ->
            service.revokeDevice(connection, principal, principal.membership.memberId, tokenId, call.requestId())
            MutationResponse(204, null)
        }
    }

    get("/api/v1/workspaces") {
        val principal = call.authenticate(service)
        call.enforceAuthenticatedReadRate(rateLimiter, principal)
        call.respond(WorkspacesResponse(listOf(principal.membership.toDto())))
    }

    get("/api/v1/workspaces/{workspaceId}") {
        val principal = call.authenticate(service)
        call.enforceAuthenticatedReadRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.READ)
        call.respond(principal.membership.toDto())
    }

    if (markerService != null) {
        get("/api/v1/workspaces/{workspaceId}/markers") {
            val principal = call.authenticate(service)
            call.enforceAuthenticatedReadRate(rateLimiter, principal)
            val workspaceId = canonicalUuid(call.parameters["workspaceId"])
            authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.READ)
            call.respond(markerService.listSnapshot(workspaceId).toResponse())
        }

        post("/api/v1/workspaces/{workspaceId}/markers") {
            val principal = call.authenticate(service)
            val workspaceId = canonicalUuid(call.parameters["workspaceId"])
            authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.MARKER_WRITE)
            val body = call.receiveStrictJson<CreateSharedMarkerRequest>()
            call.executeMutation(
                service,
                principal,
                call.requireIdempotencyKey(),
                mutationFingerprint("POST", "/api/v1/workspaces/$workspaceId/markers", body.json),
                requiredCapability = WorkspaceCapability.MARKER_WRITE,
                beforeRespond = { result ->
                    val markerId = result.response.responseBody!!.jsonObject.getValue("markerId").jsonPrimitive.content
                    response.header(HttpHeaders.Location, "/api/v1/workspaces/$workspaceId/markers/$markerId")
                },
            ) { connection ->
                call.enforceMarkerWriteRate(rateLimiter, principal)
                val marker = markerService.create(
                    connection = connection,
                    actor = principal,
                    systemId = body.value.systemId,
                    name = body.value.name,
                    color = body.value.color,
                    tags = body.value.tags,
                    notes = body.value.notes,
                    requestId = call.requestId(),
                )
                MutationResponse(201, PROTOCOL_JSON.encodeToJsonElement(marker.toDto()))
            }
        }

        patch("/api/v1/workspaces/{workspaceId}/markers/{markerId}") {
            val principal = call.authenticate(service)
            val workspaceId = canonicalUuid(call.parameters["workspaceId"])
            authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.MARKER_WRITE)
            val markerId = canonicalUuid(call.parameters["markerId"])
            val body = call.receiveStrictJson<UpdateSharedMarkerRequest>()
            call.executeMutation(
                service,
                principal,
                call.requireIdempotencyKey(),
                mutationFingerprint(
                    "PATCH",
                    "/api/v1/workspaces/$workspaceId/markers/$markerId",
                    body.json,
                ),
                requiredCapability = WorkspaceCapability.MARKER_WRITE,
            ) { connection ->
                call.enforceMarkerWriteRate(rateLimiter, principal)
                val marker = markerService.update(
                    connection = connection,
                    actor = principal,
                    markerId = markerId,
                    expectedVersion = body.value.expectedVersion,
                    name = body.value.name,
                    color = body.value.color,
                    tags = body.value.tags,
                    notes = body.value.notes,
                    requestId = call.requestId(),
                )
                MutationResponse(200, PROTOCOL_JSON.encodeToJsonElement(marker.toDto()))
            }
        }

        delete("/api/v1/workspaces/{workspaceId}/markers/{markerId}") {
            val principal = call.authenticate(service)
            val workspaceId = canonicalUuid(call.parameters["workspaceId"])
            authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.MARKER_WRITE)
            val markerId = canonicalUuid(call.parameters["markerId"])
            val expectedVersion = call.request.queryParameters["expectedVersion"]?.toLongOrNull()
                ?: throw ServiceException(400, "INVALID_ARGUMENT", "expectedVersion is required.")
            call.executeMutation(
                service,
                principal,
                call.requireIdempotencyKey(),
                mutationFingerprint(
                    "DELETE",
                    "/api/v1/workspaces/$workspaceId/markers/$markerId",
                    versionQuery = expectedVersion,
                ),
                requiredCapability = WorkspaceCapability.MARKER_WRITE,
            ) { connection ->
                call.enforceMarkerWriteRate(rateLimiter, principal)
                markerService.delete(connection, principal, markerId, expectedVersion, call.requestId())
                MutationResponse(204, null)
            }
        }
    }

    if (routeHandoffService != null) {
        get("/api/v1/workspaces/{workspaceId}/route-handoffs") {
            val principal = call.authenticate(service)
            call.enforceAuthenticatedReadRate(rateLimiter, principal)
            val workspaceId = canonicalUuid(call.parameters["workspaceId"])
            authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.READ)
            call.respond(
                RouteHandoffListResponse(
                    generatedAt = java.time.Instant.now().toString(),
                    routeHandoffs = routeHandoffService.listRecent(workspaceId).map { it.toDto() },
                ),
            )
        }

        post("/api/v1/workspaces/{workspaceId}/route-handoffs") {
            val principal = call.authenticate(service)
            val workspaceId = canonicalUuid(call.parameters["workspaceId"])
            authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ROUTE_HANDOFF_WRITE)
            val body = call.receiveStrictJson<PublishRouteHandoffRequest>()
            call.executeMutation(
                service,
                principal,
                call.requireIdempotencyKey(),
                mutationFingerprint("POST", "/api/v1/workspaces/$workspaceId/route-handoffs", body.json),
                requiredCapability = WorkspaceCapability.ROUTE_HANDOFF_WRITE,
            ) { connection ->
                call.enforceMarkerWriteRate(rateLimiter, principal)
                val handoff = routeHandoffService.publish(connection, principal, body.value.toDraft(), call.requestId())
                MutationResponse(201, PROTOCOL_JSON.encodeToJsonElement(handoff.toDto()))
            }
        }
    }

    get("/api/v1/workspaces/{workspaceId}/members") {
        val principal = call.authenticate(service)
        call.enforceAuthenticatedReadRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        call.respond(MembersResponse(service.listMembers(workspaceId).map { it.toDto() }))
    }

    post("/api/v1/workspaces/{workspaceId}/members") {
        val principal = call.authenticate(service)
        call.enforceAdminWriteRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        val body = call.receiveStrictJson<CreateMemberRequest>()
        val role = parseRole(body.value.role)
        call.executeMutation(
            service,
            principal,
            call.requireIdempotencyKey(),
            mutationFingerprint("POST", "/api/v1/workspaces/{workspaceId}/members", body.json),
            requiredCapability = WorkspaceCapability.ADMIN,
        ) { connection ->
            val member = service.createMember(
                connection,
                principal,
                body.value.displayName,
                role,
                call.requestId(),
            )
            MutationResponse(201, PROTOCOL_JSON.encodeToJsonElement(member.toDto()))
        }
    }

    patch("/api/v1/workspaces/{workspaceId}/members/{memberId}") {
        val principal = call.authenticate(service)
        call.enforceAdminWriteRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        val memberId = canonicalUuid(call.parameters["memberId"])
        val body = call.receiveStrictJson<UpdateMemberRequest>()
        val role = body.value.role?.let(::parseRole)
        call.executeMutation(
            service,
            principal,
            call.requireIdempotencyKey(),
            mutationFingerprint("PATCH", "/api/v1/workspaces/{workspaceId}/members/{memberId}", body.json),
            requiredCapability = WorkspaceCapability.ADMIN,
        ) { connection ->
            val member = service.updateMember(
                connection,
                principal,
                memberId,
                body.value.expectedVersion,
                body.value.displayName,
                role,
                call.requestId(),
            )
            MutationResponse(200, PROTOCOL_JSON.encodeToJsonElement(member.toDto()))
        }
    }

    delete("/api/v1/workspaces/{workspaceId}/members/{memberId}") {
        val principal = call.authenticate(service)
        call.enforceAdminWriteRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        val memberId = canonicalUuid(call.parameters["memberId"])
        val expectedVersion = call.request.queryParameters["expectedVersion"]?.toLongOrNull()
            ?: throw ServiceException(400, "INVALID_ARGUMENT", "expectedVersion is required.")
        call.executeMutation(
            service,
            principal,
            call.requireIdempotencyKey(),
            mutationFingerprint(
                "DELETE",
                "/api/v1/workspaces/{workspaceId}/members/{memberId}",
                versionQuery = expectedVersion,
            ),
            requiredCapability = WorkspaceCapability.ADMIN,
        ) { connection ->
            service.revokeMember(connection, principal, memberId, expectedVersion, call.requestId())
            MutationResponse(204, null)
        }
    }

    get("/api/v1/workspaces/{workspaceId}/invites") {
        val principal = call.authenticate(service)
        call.enforceAuthenticatedReadRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        call.respond(InvitesResponse(service.listInvites(workspaceId).map { it.toDto() }))
    }

    post("/api/v1/workspaces/{workspaceId}/members/{memberId}/invites") {
        val principal = call.authenticate(service)
        call.enforceAdminWriteRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        val memberId = canonicalUuid(call.parameters["memberId"])
        val body = call.receiveStrictJson<CreateInviteRequest>()
        val lifetime = try {
            Duration.ofHours(body.value.expiresInHours)
        } catch (_: ArithmeticException) {
            throw ServiceException(422, "INVALID_ARGUMENT", "One or more fields are invalid.")
        }
        call.executeMutation(
            service,
            principal,
            call.requireIdempotencyKey(),
            mutationFingerprint(
                "POST",
                "/api/v1/workspaces/{workspaceId}/members/{memberId}/invites",
                body.json,
            ),
            requiredCapability = WorkspaceCapability.ADMIN,
            nonReplayableSecretResponse = true,
        ) { connection ->
            val invite = service.createInvite(connection, principal, memberId, lifetime, call.requestId())
            val response = InviteCreatedResponse(
                inviteId = invite.metadata.inviteId.toString(),
                inviteToken = invite.rawSecret,
                memberId = invite.metadata.memberId.toString(),
                expiresAt = invite.metadata.expiresAt.toString(),
                createdAt = invite.metadata.createdAt.toString(),
            )
            val safeMetadata = buildJsonObject {
                put("inviteId", invite.metadata.inviteId.toString())
                put("workspaceId", invite.metadata.workspaceId.toString())
                put("memberId", invite.metadata.memberId.toString())
                put("userId", invite.metadata.userId.toString())
                put("displayName", invite.metadata.displayName)
                put("role", invite.metadata.role.name)
                put("expiresAt", invite.metadata.expiresAt.toString())
                put("status", invite.metadata.status.name)
            }
            MutationResponse(
                201,
                PROTOCOL_JSON.encodeToJsonElement(response),
                storageBody = safeMetadata,
            )
        }
    }

    delete("/api/v1/workspaces/{workspaceId}/invites/{inviteId}") {
        val principal = call.authenticate(service)
        call.enforceAdminWriteRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        val inviteId = canonicalUuid(call.parameters["inviteId"])
        call.executeMutation(
            service,
            principal,
            call.requireIdempotencyKey(),
            mutationFingerprint("DELETE", "/api/v1/workspaces/{workspaceId}/invites/{inviteId}"),
            requiredCapability = WorkspaceCapability.ADMIN,
        ) { connection ->
            service.revokeInvite(connection, principal, inviteId, call.requestId())
            MutationResponse(204, null)
        }
    }

    get("/api/v1/workspaces/{workspaceId}/members/{memberId}/devices") {
        val principal = call.authenticate(service)
        call.enforceAuthenticatedReadRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        val memberId = canonicalUuid(call.parameters["memberId"])
        if (service.listMembers(workspaceId).none { it.memberId == memberId }) throw ServiceException(
            404,
            "NOT_FOUND",
            "The requested resource was not found.",
        )
        call.respond(DevicesResponse(service.listDevices(memberId).map { it.toDto() }))
    }

    delete("/api/v1/workspaces/{workspaceId}/members/{memberId}/devices/{tokenId}") {
        val principal = call.authenticate(service)
        call.enforceAdminWriteRate(rateLimiter, principal)
        val workspaceId = canonicalUuid(call.parameters["workspaceId"])
        authorization.requireWorkspace(principal, workspaceId, WorkspaceCapability.ADMIN)
        val memberId = canonicalUuid(call.parameters["memberId"])
        val tokenId = canonicalUuid(call.parameters["tokenId"])
        call.executeMutation(
            service,
            principal,
            call.requireIdempotencyKey(),
            mutationFingerprint(
                "DELETE",
                "/api/v1/workspaces/{workspaceId}/members/{memberId}/devices/{tokenId}",
            ),
            requiredCapability = WorkspaceCapability.ADMIN,
        ) { connection ->
            service.revokeDevice(connection, principal, memberId, tokenId, call.requestId())
            MutationResponse(204, null)
        }
    }
}

private fun parseRole(raw: String): WorkspaceRole = try {
    WorkspaceRole.valueOf(raw)
} catch (_: IllegalArgumentException) {
    throw ServiceException(422, "INVALID_ARGUMENT", "One or more fields are invalid.")
}

private fun ApplicationCall.authenticate(service: SharedMapService): AuthenticationPrincipal {
    val authorization = request.headers[HttpHeaders.Authorization] ?: throw dev.evesharedmap.server.service.ServiceErrors
        .unauthenticated()
    val rawToken = if (authorization.startsWith("Bearer ", ignoreCase = true)) authorization.substring(7) else ""
    if (rawToken.isBlank() || rawToken.any(Char::isWhitespace)) {
        throw dev.evesharedmap.server.service.ServiceErrors.unauthenticated()
    }
    return service.authenticate(rawToken)
}

private fun ApplicationCall.requestId(): String = callId ?: "unavailable"

private suspend fun ApplicationCall.executeMutation(
    service: SharedMapService,
    principal: AuthenticationPrincipal,
    key: UUID,
    fingerprint: ByteArray,
    requiredCapability: WorkspaceCapability,
    nonReplayableSecretResponse: Boolean = false,
    beforeRespond: ApplicationCall.(IdempotentMutationResult) -> Unit = {},
    operation: (java.sql.Connection) -> MutationResponse,
) {
    val result = try {
        service.executeIdempotent(
            principal.tokenId,
            key,
            fingerprint,
            requiredCapability,
            nonReplayableSecretResponse,
            operation,
        )
    } finally {
        fingerprint.fill(0)
    }
    beforeRespond(result)
    respondMutation(result)
}

private suspend fun ApplicationCall.respondMutation(result: IdempotentMutationResult) {
    val status = HttpStatusCode.fromValue(result.response.status)
    if (result.response.responseBody == null) respond(status)
    else respond(status, result.response.responseBody)
}

private fun ApplicationCall.enforceInviteExchangeRate(rateLimiter: RateLimiter) {
    val remote = request.origin.remoteHost
    enforceRate(rateLimiter, "invite-minute:$remote", RateLimits.INVITE_EXCHANGE_PER_MINUTE, RateLimits.MINUTE)
    enforceRate(rateLimiter, "invite-day:$remote", RateLimits.INVITE_EXCHANGE_PER_DAY, RateLimits.DAY)
}

private fun ApplicationCall.enforceAuthenticatedReadRate(
    rateLimiter: RateLimiter,
    principal: AuthenticationPrincipal,
) = enforceRate(
    rateLimiter,
    "authenticated-read:${principal.tokenId}",
    RateLimits.AUTHENTICATED_READS_PER_MINUTE,
    RateLimits.MINUTE,
)

private fun ApplicationCall.enforceAdminWriteRate(
    rateLimiter: RateLimiter,
    principal: AuthenticationPrincipal,
) = enforceRate(
    rateLimiter,
    "admin-write:${principal.tokenId}",
    RateLimits.ADMIN_WRITES_PER_MINUTE,
    RateLimits.MINUTE,
)

private fun ApplicationCall.enforceMarkerWriteRate(
    rateLimiter: RateLimiter,
    principal: AuthenticationPrincipal,
) = enforceRate(
    rateLimiter,
    "marker-write:${principal.tokenId}",
    RateLimits.MARKER_WRITES_PER_MINUTE,
    RateLimits.MINUTE,
)

private fun ApplicationCall.enforceRate(
    rateLimiter: RateLimiter,
    key: String,
    capacity: Int,
    period: Duration,
) {
    val decision = rateLimiter.consume(key, capacity, period)
    if (!decision.allowed) {
        response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        throw ServiceException(429, "RATE_LIMITED", "The request rate limit was exceeded.")
    }
}
