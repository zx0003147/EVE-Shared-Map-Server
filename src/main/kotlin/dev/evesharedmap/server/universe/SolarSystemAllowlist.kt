package dev.evesharedmap.server.universe

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class SolarSystemAllowlist private constructor(
    val universeBuild: String,
    val source: String,
    val sourceSha256: String,
    private val systemIds: Set<Int>,
) {
    val size: Int get() = systemIds.size

    operator fun contains(systemId: Int): Boolean = systemId in systemIds

    companion object {
        const val DEFAULT_RESOURCE = "universe/solar-system-allowlist-v1.txt"

        fun load(
            resourceName: String = DEFAULT_RESOURCE,
            classLoader: ClassLoader = SolarSystemAllowlist::class.java.classLoader,
        ): SolarSystemAllowlist {
            val stream = classLoader.getResourceAsStream(resourceName)
                ?: throw IllegalStateException("The packaged solar-system allowlist is missing.")
            return stream.use(::parse)
        }

        internal fun parse(stream: InputStream): SolarSystemAllowlist = try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                parse(reader)
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: Throwable) {
            throw IllegalStateException("The packaged solar-system allowlist is invalid.")
        }

        private fun parse(reader: BufferedReader): SolarSystemAllowlist {
            val metadata = linkedMapOf<String, String>()
            var lineNumber = 0
            while (true) {
                val line = reader.readLine() ?: invalid()
                lineNumber += 1
                if (line.isBlank()) break
                val separator = line.indexOf('=')
                if (separator <= 0 || separator == line.lastIndex) invalid()
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                if (key in metadata) invalid()
                metadata[key] = value
            }

            if (metadata.keys != REQUIRED_METADATA_KEYS) invalid()
            if (metadata.getValue("format") != "1") invalid()
            val universeBuild = metadata.getValue("universeBuild")
            val source = metadata.getValue("source")
            val sourceSha256 = metadata.getValue("sourceSha256")
            val expectedCount = metadata.getValue("systemCount").toIntOrNull() ?: invalid()
            if (universeBuild.isBlank() || source.isBlank() || !SHA256.matches(sourceSha256)) invalid()
            if (expectedCount <= 0) invalid()

            val ids = linkedSetOf<Int>()
            reader.forEachLine { raw ->
                lineNumber += 1
                if (raw.isBlank() || !POSITIVE_INTEGER.matches(raw)) invalid()
                val systemId = raw.toIntOrNull() ?: invalid()
                if (systemId <= 0 || !ids.add(systemId)) invalid()
            }
            if (ids.size != expectedCount) invalid()

            return SolarSystemAllowlist(
                universeBuild = universeBuild,
                source = source,
                sourceSha256 = sourceSha256.lowercase(),
                systemIds = ids.toSet(),
            )
        }

        private fun invalid(): Nothing =
            throw IllegalStateException("The packaged solar-system allowlist is invalid.")

        private val REQUIRED_METADATA_KEYS = linkedSetOf(
            "format",
            "universeBuild",
            "source",
            "sourceSha256",
            "systemCount",
        )
        private val SHA256 = Regex("[0-9A-Fa-f]{64}")
        private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
    }
}
