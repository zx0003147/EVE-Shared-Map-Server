package dev.evesharedmap.server.logging

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import org.slf4j.MDC

private val requestStartedAtKey = AttributeKey<Long>("StructuredAccessLogStartedAt")

private val StructuredAccessLogging = createApplicationPlugin(name = "StructuredAccessLogging") {
    val logger = LoggerFactory.getLogger("http.access")

    onCall { call ->
        call.attributes.put(requestStartedAtKey, System.nanoTime())
    }

    on(ResponseSent) { call ->
        val startedAt = call.attributes.getOrNull(requestStartedAtKey) ?: System.nanoTime()
        val fields = mapOf(
            "requestId" to (call.callId ?: "unavailable"),
            "method" to call.request.httpMethod.value,
            "route" to LogSanitizer.routeTemplate(call.request.path()),
            "status" to (call.response.status()?.value?.toString() ?: "unresolved"),
            "durationMs" to ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L).toString(),
        )
        val previousContext = MDC.getCopyOfContextMap()
        try {
            fields.forEach(MDC::put)
            logger.info("http_request")
        } finally {
            MDC.clear()
            previousContext?.forEach(MDC::put)
        }
    }
}

fun Application.installStructuredAccessLogging() {
    install(StructuredAccessLogging)
}
