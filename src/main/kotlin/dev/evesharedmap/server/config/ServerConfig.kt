package dev.evesharedmap.server.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class ServerConfig(
    val bindHost: String,
    val port: Int,
    val database: DatabaseConfig,
    val environment: String,
    val logLevel: LogLevel,
    val tokenPepper: SecretValue,
) {
    companion object {
        private const val DEFAULT_BIND_HOST = "0.0.0.0"
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_ENVIRONMENT = "development"
        private const val MAX_SECRET_BYTES = 4 * 1024L

        fun load(environment: Map<String, String> = System.getenv()): ServerConfig {
            val bindHost = environment.valueOrNull("SHARED_MAP_BIND_HOST") ?: DEFAULT_BIND_HOST
            val port = environment.valueOrNull("SHARED_MAP_PORT")
                ?.toIntOrNull()
                ?: if (environment.containsKey("SHARED_MAP_PORT")) {
                    throw ConfigurationException("SHARED_MAP_PORT must be an integer from 1 through 65535.")
                } else {
                    DEFAULT_PORT
                }
            if (port !in 1..65535) {
                throw ConfigurationException("SHARED_MAP_PORT must be an integer from 1 through 65535.")
            }

            val databaseUrl = environment.required("SHARED_MAP_DATABASE_URL")
            if (!databaseUrl.startsWith("jdbc:postgresql://")) {
                throw ConfigurationException("SHARED_MAP_DATABASE_URL must be a PostgreSQL JDBC URL.")
            }
            val databaseUser = readDatabaseUser(environment)
            val password = readRequiredSecret(
                environment.required("SHARED_MAP_DATABASE_PASSWORD_FILE"),
                "SHARED_MAP_DATABASE_PASSWORD_FILE",
            )
            val tokenPepper = try {
                readRequiredSecret(
                    environment.required("SHARED_MAP_TOKEN_PEPPER_FILE"),
                    "SHARED_MAP_TOKEN_PEPPER_FILE",
                )
            } catch (error: Throwable) {
                password.close()
                throw error
            }

            return ServerConfig(
                bindHost = bindHost,
                port = port,
                database = DatabaseConfig(
                    url = databaseUrl,
                    user = databaseUser,
                    password = password,
                ),
                environment = environment.valueOrNull("SHARED_MAP_ENVIRONMENT") ?: DEFAULT_ENVIRONMENT,
                logLevel = LogLevel.parse(environment.valueOrNull("SHARED_MAP_LOG_LEVEL")),
                tokenPepper = tokenPepper,
            )
        }

        private fun readDatabaseUser(environment: Map<String, String>): String {
            val directValue = environment.valueOrNull("SHARED_MAP_DATABASE_USER")
            val filePath = environment.valueOrNull("SHARED_MAP_DATABASE_USER_FILE")
            if (directValue != null && filePath != null) {
                throw ConfigurationException(
                    "Set only one of SHARED_MAP_DATABASE_USER or SHARED_MAP_DATABASE_USER_FILE.",
                )
            }
            if (filePath == null) {
                return directValue ?: throw ConfigurationException(
                    "SHARED_MAP_DATABASE_USER or SHARED_MAP_DATABASE_USER_FILE is required.",
                )
            }

            val bytes = readSecretBytes(
                safePath(filePath, "SHARED_MAP_DATABASE_USER_FILE"),
                "SHARED_MAP_DATABASE_USER_FILE",
            )
            val value = bytes.toString(StandardCharsets.UTF_8).trimEnd('\r', '\n')
            bytes.fill(0)
            if (value.isBlank()) {
                throw ConfigurationException(
                    "SHARED_MAP_DATABASE_USER_FILE must reference a readable, non-empty file.",
                )
            }
            return value
        }

        private fun readRequiredSecret(rawPath: String, key: String): SecretValue {
            val path = safePath(rawPath, key)
            val bytes = readSecretBytes(path, key)
            val value = bytes.toString(StandardCharsets.UTF_8).trimEnd('\r', '\n')
            bytes.fill(0)
            if (value.isBlank()) {
                throw ConfigurationException(
                    "$key must reference a readable, non-empty secret file.",
                )
            }
            return SecretValue.from(value)
        }

        private fun safePath(rawPath: String, key: String): Path = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            throw ConfigurationException("$key must reference a readable, non-empty secret file.")
        }

        private fun readSecretBytes(path: Path, key: String): ByteArray {
            try {
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                    throw ConfigurationException("$key must reference a readable, non-empty secret file.")
                }
                val size = Files.size(path)
                if (size == 0L || size > MAX_SECRET_BYTES) {
                    throw ConfigurationException("$key must reference a readable, non-empty secret file.")
                }
                return Files.readAllBytes(path)
            } catch (exception: ConfigurationException) {
                throw exception
            } catch (_: Exception) {
                throw ConfigurationException("$key must reference a readable, non-empty secret file.")
            }
        }
    }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: SecretValue,
)

enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    companion object {
        fun parse(value: String?): LogLevel {
            if (value == null) return INFO
            return entries.firstOrNull { it.name == value.uppercase() }
                ?: throw ConfigurationException("SHARED_MAP_LOG_LEVEL must be TRACE, DEBUG, INFO, WARN, or ERROR.")
        }
    }
}

class ConfigurationException(message: String) : IllegalArgumentException(message)

private fun Map<String, String>.required(key: String): String = valueOrNull(key)
    ?: throw ConfigurationException("$key is required.")

private fun Map<String, String>.valueOrNull(key: String): String? = this[key]?.takeIf { it.isNotBlank() }
