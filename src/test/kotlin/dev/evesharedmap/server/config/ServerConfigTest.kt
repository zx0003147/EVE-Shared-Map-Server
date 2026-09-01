package dev.evesharedmap.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerConfigTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `valid environment loads typed configuration`() {
        val passwordFile = writeSecret("local-test-password")
        val pepperFile = writeSecret("reserved-future-pepper", "pepper.txt")

        val config = ServerConfig.load(
            validEnvironment(passwordFile) + mapOf(
                "SHARED_MAP_BIND_HOST" to "127.0.0.1",
                "SHARED_MAP_PORT" to "9080",
                "SHARED_MAP_ENVIRONMENT" to "test",
                "SHARED_MAP_LOG_LEVEL" to "warn",
                "SHARED_MAP_TOKEN_PEPPER_FILE" to pepperFile.toString(),
            ),
        )

        assertEquals("127.0.0.1", config.bindHost)
        assertEquals(9080, config.port)
        assertEquals("jdbc:postgresql://localhost:54329/eve_shared_map", config.database.url)
        assertEquals("eve_shared_map", config.database.user)
        assertEquals("<redacted>", config.database.password.toString())
        assertEquals("test", config.environment)
        assertEquals(LogLevel.WARN, config.logLevel)
        assertEquals(pepperFile, config.reservedTokenPepperFile)
        config.database.password.close()
    }

    @Test
    fun `missing database URL fails clearly`() {
        val environment = validEnvironment(writeSecret("local-test-password")) - "SHARED_MAP_DATABASE_URL"

        val error = assertFailsWith<ConfigurationException> { ServerConfig.load(environment) }

        assertEquals("SHARED_MAP_DATABASE_URL is required.", error.message)
    }

    @Test
    fun `non PostgreSQL JDBC URL is rejected`() {
        val environment = validEnvironment(writeSecret("local-test-password")) +
            ("SHARED_MAP_DATABASE_URL" to "jdbc:sqlite:local.db")

        val error = assertFailsWith<ConfigurationException> { ServerConfig.load(environment) }

        assertEquals("SHARED_MAP_DATABASE_URL must be a PostgreSQL JDBC URL.", error.message)
    }

    @Test
    fun `invalid port is rejected`() {
        val environment = validEnvironment(writeSecret("local-test-password")) +
            ("SHARED_MAP_PORT" to "70000")

        val error = assertFailsWith<ConfigurationException> { ServerConfig.load(environment) }

        assertEquals("SHARED_MAP_PORT must be an integer from 1 through 65535.", error.message)
    }

    @Test
    fun `missing password file fails without disclosing its path`() {
        val recognizablePathSecret = "DO_NOT_LEAK_PASSWORD_PATH"
        val environment = validEnvironment(tempDirectory.resolve(recognizablePathSecret))

        val error = assertFailsWith<ConfigurationException> { ServerConfig.load(environment) }

        assertFalse(error.toString().contains(recognizablePathSecret))
        assertFalse(error.stackTraceToString().contains("local-test-password"))
    }

    @Test
    fun `blank password file fails without disclosing content`() {
        val recognizableSecret = "RECOGNIZABLE_FAKE_SECRET"
        val blankFile = writeSecret("\r\n", "$recognizableSecret.txt")

        val error = assertFailsWith<ConfigurationException> {
            ServerConfig.load(validEnvironment(blankFile))
        }

        assertFalse(error.toString().contains(recognizableSecret))
        assertFalse(error.toString().contains(blankFile.toString()))
    }

    private fun validEnvironment(passwordFile: Path): Map<String, String> = mapOf(
        "SHARED_MAP_DATABASE_URL" to "jdbc:postgresql://localhost:54329/eve_shared_map",
        "SHARED_MAP_DATABASE_USER" to "eve_shared_map",
        "SHARED_MAP_DATABASE_PASSWORD_FILE" to passwordFile.toString(),
    )

    private fun writeSecret(value: String, name: String = "db-password.txt"): Path =
        tempDirectory.resolve(name).also { Files.writeString(it, value) }
}
