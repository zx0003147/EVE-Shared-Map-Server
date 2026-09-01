package dev.evesharedmap.server.meta

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

private const val PROTOCOL_VERSION = 1

@Serializable
data class MetaResponse(
    val serverVersion: String,
    val protocolVersion: Int,
    val minimumClientProtocolVersion: Int,
    val maximumClientProtocolVersion: Int,
    val features: List<String>,
)

fun Route.metaRoutes(
    serverVersion: String,
    beforeRequest: suspend io.ktor.server.application.ApplicationCall.() -> Unit = {},
) {
    get("/api/v1/meta") {
        call.beforeRequest()
        call.respond(
            MetaResponse(
                serverVersion = serverVersion,
                protocolVersion = PROTOCOL_VERSION,
                minimumClientProtocolVersion = PROTOCOL_VERSION,
                maximumClientProtocolVersion = PROTOCOL_VERSION,
                features = listOf("members", "invites", "device-revocation"),
            ),
        )
    }
}
