package dev.evesharedmap.server.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.evesharedmap.server.health.ReadinessProbe
import dev.evesharedmap.server.http.configureHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingSecurityTest {
    @Test
    fun `sanitized failure logger omits exception message`() {
        val fakeSecret = "RECOGNIZABLE_FAKE_DATABASE_PASSWORD"
        val (logger, appender) = capturingLogger("security.failure.test")
        try {
            logger.logSanitizedError("startup_failed", IllegalStateException(fakeSecret))

            val captured = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(captured.contains("startup_failed"))
            assertTrue(captured.contains("errorCategory=internal"))
            assertFalse(captured.contains(fakeSecret))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `HTTP access log omits authorization header and exposes structured fields`() = testApplication {
        val fakeBearer = "Bearer RECOGNIZABLE_FAKE_BEARER_TOKEN"
        val (logger, appender) = capturingLogger("http.access")
        try {
            application { configureHttp(ReadinessProbe { true }, "0.1.0-SNAPSHOT") }

            client.get("/health") {
                header(HttpHeaders.Authorization, fakeBearer)
                header("X-Request-Id", "safe-test-request-id")
            }

            val captured = appender.list.joinToString("\n") { event ->
                event.formattedMessage + event.mdcPropertyMap.entries.joinToString()
            }
            assertFalse(captured.contains(fakeBearer))
            assertFalse(captured.contains("Authorization", ignoreCase = true))
            val accessEvent = appender.list.last { it.formattedMessage == "http_request" }
            assertEquals("safe-test-request-id", accessEvent.mdcPropertyMap["requestId"])
            assertEquals("GET", accessEvent.mdcPropertyMap["method"])
            assertEquals("/health", accessEvent.mdcPropertyMap["route"])
            assertEquals("200", accessEvent.mdcPropertyMap["status"])
            assertTrue(accessEvent.mdcPropertyMap.containsKey("durationMs"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `HTTP access log records final unavailable status`() = testApplication {
        val (logger, appender) = capturingLogger("http.access")
        try {
            application { configureHttp(ReadinessProbe { false }, "0.1.0-SNAPSHOT") }

            client.get("/health")

            val accessEvent = appender.list.last { it.formattedMessage == "http_request" }
            assertEquals("503", accessEvent.mdcPropertyMap["status"])
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `secret-bearing auth request and response never enter access logs`() = testApplication {
        val inviteSecret = "esm_inv_RECOGNIZABLE_ONE_TIME_INVITE_SECRET"
        val deviceSecret = "esm_dev_RECOGNIZABLE_DEVICE_ACCESS_TOKEN"
        val databasePassword = "RECOGNIZABLE_DATABASE_PASSWORD"
        val (logger, appender) = capturingLogger("http.access")
        try {
            application {
                configureHttp(ReadinessProbe { true }, "0.2.0-test") {
                    post("/__test/secret-response") {
                        call.receiveText()
                        call.respondText("""{"accessToken":"$deviceSecret"}""", ContentType.Application.Json)
                    }
                }
            }

            client.post("/__test/secret-response") {
                header(HttpHeaders.Authorization, "Bearer $deviceSecret")
                contentType(ContentType.Application.Json)
                setBody("""{"inviteToken":"$inviteSecret","databasePassword":"$databasePassword"}""")
            }

            val captured = appender.list.joinToString("\n") { event ->
                event.formattedMessage + event.mdcPropertyMap.entries.joinToString()
            }
            assertFalse(captured.contains(inviteSecret))
            assertFalse(captured.contains(deviceSecret))
            assertFalse(captured.contains(databasePassword))
            assertFalse(captured.contains("Authorization", ignoreCase = true))
            assertFalse(captured.contains("inviteToken", ignoreCase = true))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun capturingLogger(name: String): Pair<Logger, ListAppender<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(name) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return logger to appender
    }
}
