package dev.evesharedmap.server

import java.util.Properties

object BuildInfo {
    val serverVersion: String by lazy {
        val properties = Properties()
        val stream = checkNotNull(BuildInfo::class.java.getResourceAsStream("/server-version.properties")) {
            "Server version resource is missing."
        }
        stream.use(properties::load)
        checkNotNull(properties.getProperty("serverVersion")) { "Server version is missing." }
    }
}
