package dev.evesharedmap.server.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.evesharedmap.server.health.ReadinessProbe
import dev.evesharedmap.server.http.configureHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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
            assertTrue(accessEvent.mdcPropertyMap.containsKey("status"))
            assertTrue(accessEvent.mdcPropertyMap.containsKey("durationMs"))
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
