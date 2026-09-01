package dev.evesharedmap.server.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant

@Serializable
data class HealthResponse(
    val status: String,
    val serverVersion: String,
    val serverTime: String,
    val checks: HealthChecks,
)

@Serializable
data class HealthChecks(
    val database: String,
)

fun Route.healthRoutes(
    readinessProbe: ReadinessProbe,
    serverVersion: String,
    clock: Clock = Clock.systemUTC(),
) {
    get("/health") {
        val ready = readinessProbe.databaseReady()
        val response = HealthResponse(
            status = if (ready) "ok" else "unavailable",
            serverVersion = serverVersion,
            serverTime = Instant.now(clock).toString(),
            checks = HealthChecks(database = if (ready) "ok" else "unavailable"),
        )
        call.respond(if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, response)
    }
}
