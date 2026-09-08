package dev.evesharedmap.server.http

import dev.evesharedmap.server.api.sharedMapRoutes
import dev.evesharedmap.server.config.AllowedWebOrigin
import dev.evesharedmap.server.health.ReadinessProbe
import dev.evesharedmap.server.health.healthRoutes
import dev.evesharedmap.server.logging.installStructuredAccessLogging
import dev.evesharedmap.server.marker.SharedMarkerService
import dev.evesharedmap.server.meta.metaRoutes
import dev.evesharedmap.server.security.InMemoryTokenBucketRateLimiter
import dev.evesharedmap.server.security.RateLimiter
import dev.evesharedmap.server.security.RateLimits
import dev.evesharedmap.server.service.ServiceException
import dev.evesharedmap.server.service.SharedMapService
import dev.evesharedmap.server.universe.SolarSystemAllowlist
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
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
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Clock

private const val MAX_REQUEST_BODY_BYTES = 32L * 1024L

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val requestId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val details: JsonObject? = null,
)

fun Application.configureHttp(
    readinessProbe: ReadinessProbe,
    serverVersion: String,
    clock: Clock = Clock.systemUTC(),
    sharedMapService: SharedMapService? = null,
    sharedMarkerService: SharedMarkerService? = null,
    universeBuild: String = SolarSystemAllowlist.load().universeBuild,
    rateLimiter: RateLimiter = InMemoryTokenBucketRateLimiter(clock),
    allowedOrigins: Set<AllowedWebOrigin> = emptySet(),
    additionalRoutes: Routing.() -> Unit = {},
) {
    installRequestIdPlugin()
    installStructuredAccessLogging()

    if (allowedOrigins.isNotEmpty()) {
        install(CORS) {
            allowedOrigins.forEach { origin -> allowHost(origin.hostAndPort, schemes = listOf(origin.scheme)) }
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(REQUEST_ID_HEADER)
            allowHeader(IDEMPOTENCY_KEY_HEADER)
            exposeHeader(REQUEST_ID_HEADER)
            exposeHeader(HttpHeaders.Location)
            exposeHeader(HttpHeaders.RetryAfter)
            allowNonSimpleContentTypes = true
            allowCredentials = false
        }
    }

    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = true
                ignoreUnknownKeys = false
            },
        )
    }

    install(RequestBodyLimit) {
        bodyLimit { MAX_REQUEST_BODY_BYTES }
    }

    install(StatusPages) {
        exception<ServiceException> { call, error ->
            if (error.status == HttpStatusCode.Unauthorized.value) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            }
            call.respondSafeError(
                status = HttpStatusCode.fromValue(error.status),
                code = error.code,
                message = error.safeMessage,
                details = error.details,
            )
        }
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
        val publicRateGuard: suspend io.ktor.server.application.ApplicationCall.() -> Unit = {
            val remote = request.origin.remoteHost
            val decision = rateLimiter.consume(
                "public:$remote",
                RateLimits.PUBLIC_READS_PER_MINUTE,
                RateLimits.MINUTE,
            )
            if (!decision.allowed) {
                response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
                throw ServiceException(429, "RATE_LIMITED", "The request rate limit was exceeded.")
            }
        }
        healthRoutes(readinessProbe, serverVersion, clock, publicRateGuard)
        metaRoutes(serverVersion, universeBuild, publicRateGuard)
        if (sharedMapService != null) {
            sharedMapRoutes(sharedMapService, sharedMarkerService, rateLimiter = rateLimiter)
        }
        additionalRoutes()
    }
}

private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

private suspend fun io.ktor.server.application.ApplicationCall.respondSafeError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: JsonObject? = null,
) {
    respond(
        status,
        ApiErrorResponse(
            code = code,
            message = message,
            requestId = callId ?: "unavailable",
            details = details,
        ),
    )
}
