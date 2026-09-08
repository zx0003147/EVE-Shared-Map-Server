package dev.evesharedmap.server.http

import dev.evesharedmap.server.config.AllowedWebOrigin
import dev.evesharedmap.server.health.ReadinessProbe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsTest {
    private val allowed = AllowedWebOrigin("https", "web.example.com")

    @Test
    fun `allowed browser origin receives CORS headers and preflight supports marker mutations`() = testApplication {
        application {
            configureHttp(
                readinessProbe = ReadinessProbe { true },
                serverVersion = "0.1.0-test",
                allowedOrigins = setOf(allowed),
            )
        }

        val read = client.get("/api/v1/meta") { header(HttpHeaders.Origin, allowed.origin) }
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(allowed.origin, read.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(read.headers[HttpHeaders.AccessControlAllowCredentials])

        val preflight = client.options("/api/v1/workspaces/01991d60-b8a2-7a20-a311-b5114b27c219/markers") {
            header(HttpHeaders.Origin, allowed.origin)
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            header(
                HttpHeaders.AccessControlRequestHeaders,
                "Authorization, Content-Type, X-Request-Id, Idempotency-Key",
            )
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertEquals(allowed.origin, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `disallowed browser origin is rejected while Desktop request is unchanged`() = testApplication {
        application {
            configureHttp(
                readinessProbe = ReadinessProbe { true },
                serverVersion = "0.1.0-test",
                allowedOrigins = setOf(allowed),
            )
        }

        val rejected = client.get("/api/v1/meta") { header(HttpHeaders.Origin, "https://attacker.example") }
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertNull(rejected.headers[HttpHeaders.AccessControlAllowOrigin])

        val desktop = client.get("/api/v1/meta")
        assertEquals(HttpStatusCode.OK, desktop.status)
        assertNull(desktop.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
