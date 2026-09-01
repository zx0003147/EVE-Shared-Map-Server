package dev.evesharedmap.server.logging

import dev.evesharedmap.server.config.ConfigurationException
import org.slf4j.Logger
import java.sql.SQLException

object LogSanitizer {
    fun errorCategory(error: Throwable): String = when (error) {
        is ConfigurationException -> "configuration"
        is SQLException -> "database"
        else -> "internal"
    }

    fun routeTemplate(path: String): String = when (path) {
        "/health" -> "/health"
        "/api/v1/meta" -> "/api/v1/meta"
        else -> "<unmatched>"
    }
}

fun Logger.logSanitizedError(event: String, error: Throwable) {
    error("event={} errorCategory={}", event, LogSanitizer.errorCategory(error))
}
