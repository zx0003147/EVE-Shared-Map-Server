package dev.evesharedmap.server

import dev.evesharedmap.server.health.ReadinessProbe
import dev.evesharedmap.server.http.configureHttp
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class KtorRuntimeSmokeTest {
    @Test
    fun `Ktor application starts and serves health and meta`() = testApplication {
        application {
            configureHttp(
                readinessProbe = ReadinessProbe { true },
                serverVersion = "0.1.0-SNAPSHOT",
            )
        }

        startApplication()

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/meta").status)
    }
}
