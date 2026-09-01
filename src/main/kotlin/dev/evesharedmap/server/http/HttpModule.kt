package dev.evesharedmap.server.http

import dev.evesharedmap.server.health.ReadinessProbe
import dev.evesharedmap.server.health.healthRoutes
import dev.evesharedmap.server.logging.installStructuredAccessLogging
import dev.evesharedmap.server.meta.metaRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

private const val MAX_REQUEST_BODY_BYTES = 32L * 1024L

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val requestId: String,
)

fun Application.configureHttp(
    readinessProbe: ReadinessProbe,
    serverVersion: String,
    clock: Clock = Clock.systemUTC(),
    additionalRoutes: Routing.() -> Unit = {},
) {
    installRequestIdPlugin()
    installStructuredAccessLogging()

    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = false
            },
        )
    }

    install(RequestBodyLimit) {
        bodyLimit { MAX_REQUEST_BODY_BYTES }
    }

    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respondSafeError(
                status = HttpStatusCode.PayloadTooLarge,
                code = "PAYLOAD_TOO_LARGE",
                message = "The request body is too large.",
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondSafeError(
                status = HttpStatusCode.BadRequest,
                code = "INVALID_ARGUMENT",
                message = "The request body is malformed.",
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respondSafeError(
                status = HttpStatusCode.BadRequest,
                code = "INVALID_ARGUMENT",
                message = "The request is invalid.",
            )
        }
        exception<Throwable> { call, _ ->
            call.respondSafeError(
                status = HttpStatusCode.InternalServerError,
                code = "INTERNAL_ERROR",
                message = "The server could not complete the request.",
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondSafeError(
                status = HttpStatusCode.NotFound,
                code = "NOT_FOUND",
                message = "The requested resource was not found.",
            )
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondSafeError(
                status = HttpStatusCode.MethodNotAllowed,
                code = "INVALID_ARGUMENT",
                message = "The HTTP method is not supported for this resource.",
            )
        }
    }

    routing {
        healthRoutes(readinessProbe, serverVersion, clock)
        metaRoutes(serverVersion)
        additionalRoutes()
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSafeError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(
        status,
        ApiErrorResponse(
            code = code,
            message = message,
            requestId = callId ?: "unavailable",
        ),
    )
}
