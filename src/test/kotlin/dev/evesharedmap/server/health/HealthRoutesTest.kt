package dev.evesharedmap.server.health

import dev.evesharedmap.server.http.REQUEST_ID_HEADER
import dev.evesharedmap.server.http.configureHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthRoutesTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-09-01T11:32:18Z"), ZoneOffset.UTC)

    @Test
    fun `healthy database returns exact successful response`() = testApplication {
        application { configureHttp(ReadinessProbe { true }, VERSION, fixedClock) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType())
        assertEquals(
            """{"status":"ok","serverVersion":"$VERSION","serverTime":"2026-09-01T11:32:18Z","checks":{"database":"ok"}}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `unhealthy database returns 503 without topology`() = testApplication {
        application { configureHttp(ReadinessProbe { false }, VERSION, fixedClock) }

        val response = client.get("/health")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(
            """{"status":"unavailable","serverVersion":"$VERSION","serverTime":"2026-09-01T11:32:18Z","checks":{"database":"unavailable"}}""",
            body,
        )
        assertFalse(body.contains("jdbc:"))
        assertFalse(body.contains("localhost"))
    }

    @Test
    fun `meta returns implemented features and universe build`() = testApplication {
        application { configureHttp(ReadinessProbe { true }, VERSION, fixedClock) }

        val response = client.get("/api/v1/meta")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"serverVersion":"$VERSION","protocolVersion":1,"minimumClientProtocolVersion":1,"maximumClientProtocolVersion":1,"features":["shared-markers","members","invites","device-revocation","route-handoffs"],"universeBuild":"sde-3466501"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `safe caller request ID is adopted`() = testApplication {
        application { configureHttp(ReadinessProbe { true }, VERSION, fixedClock) }

        val response = client.get("/health") {
            header(REQUEST_ID_HEADER, "planner-request_123")
        }

        assertEquals("planner-request_123", response.headers[REQUEST_ID_HEADER])
    }

    @Test
    fun `unsafe caller request ID is replaced`() = testApplication {
        application { configureHttp(ReadinessProbe { true }, VERSION, fixedClock) }

        val response = client.get("/health") {
            header(REQUEST_ID_HEADER, "unsafe value with spaces")
        }

        val requestId = assertNotNull(response.headers[REQUEST_ID_HEADER])
        assertNotNull(UUID.fromString(requestId))
    }

    @Test
    fun `unknown and future business endpoints are absent`() = testApplication {
        application { configureHttp(ReadinessProbe { true }, VERSION, fixedClock) }

        val absentEndpoints = listOf(
            "/api/v1/auth/exchange-invite",
            "/api/v1/workspaces",
            "/api/v1/workspaces/test/markers",
        )

        absentEndpoints.forEach { endpoint ->
            val response = client.get(endpoint)
            assertEquals(HttpStatusCode.NotFound, response.status, endpoint)
            assertContains(response.bodyAsText(), "\"code\":\"NOT_FOUND\"")
        }
    }

    @Test
    fun `unsupported method is controlled`() = testApplication {
        application { configureHttp(ReadinessProbe { true }, VERSION, fixedClock) }

        val response = client.post("/health")

        assertTrue(response.status == HttpStatusCode.MethodNotAllowed || response.status == HttpStatusCode.NotFound)
        assertContains(response.bodyAsText(), "\"requestId\"")
    }

    @Test
    fun `malformed JSON returns controlled error`() = testApplication {
        application {
            configureHttp(ReadinessProbe { true }, VERSION, fixedClock) {
                post("/__test/json") {
                    call.receive<TestBody>()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        val response = client.post("/__test/json") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "\"code\":\"INVALID_ARGUMENT\"")
        assertFalse(response.bodyAsText().contains("Exception"))
    }

    @Test
    fun `internal error boundary does not leak exception text`() = testApplication {
        val recognizableSecret = "RECOGNIZABLE_INTERNAL_SECRET"
        application {
            configureHttp(ReadinessProbe { true }, VERSION, fixedClock) {
                get("/__test/failure") { error(recognizableSecret) }
            }
        }

        val response = client.get("/__test/failure")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertContains(body, "\"code\":\"INTERNAL_ERROR\"")
        assertFalse(body.contains(recognizableSecret))
        assertFalse(body.contains("IllegalStateException"))
    }

    @Test
    fun `oversized request body is rejected`() = testApplication {
        application {
            configureHttp(ReadinessProbe { true }, VERSION, fixedClock) {
                post("/__test/body") {
                    call.receive<String>()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        val response = client.post("/__test/body") {
            contentType(ContentType.Text.Plain)
            setBody("x".repeat(32 * 1024 + 1))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertContains(response.bodyAsText(), "\"code\":\"PAYLOAD_TOO_LARGE\"")
    }

    @Serializable
    private data class TestBody(val value: String)

    private companion object {
        const val VERSION = "0.1.0-SNAPSHOT"
    }
}
