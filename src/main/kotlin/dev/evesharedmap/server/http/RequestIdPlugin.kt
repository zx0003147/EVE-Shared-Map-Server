package dev.evesharedmap.server.http

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import java.util.UUID

const val REQUEST_ID_HEADER = "X-Request-Id"

private val safeRequestId = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

fun Application.installRequestIdPlugin() {
    install(CallId) {
        retrieveFromHeader(REQUEST_ID_HEADER)
        verify { candidate -> safeRequestId.matches(candidate) }
        generate { UUID.randomUUID().toString() }
        replyToHeader(REQUEST_ID_HEADER)
    }
}
