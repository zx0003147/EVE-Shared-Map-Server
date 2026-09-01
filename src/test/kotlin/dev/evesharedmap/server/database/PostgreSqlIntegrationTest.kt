package dev.evesharedmap.server.database

import dev.evesharedmap.server.config.DatabaseConfig
import dev.evesharedmap.server.config.SecretValue
import dev.evesharedmap.server.health.DatabaseReadiness
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `clean PostgreSQL migrates validates repeats and answers health`() {
        val dataSource = createDataSource()
        try {
            val first = FlywayMigrator(dataSource).migrateAndValidate()
            val second = FlywayMigrator(dataSource).migrateAndValidate()

            assertEquals(3, first.migrationsExecuted)
            assertEquals("3", first.currentVersion)
            assertEquals(0, second.migrationsExecuted)
            assertEquals("3", second.currentVersion)
            assertTrue(runBlocking { DatabaseReadiness(dataSource).databaseReady() })
            assertEquals(
                setOf(
                    "flyway_schema_history",
                    "users",
                    "workspaces",
                    "workspace_members",
                    "invites",
                    "access_tokens",
                    "audit_events",
                    "idempotency_records",
                    "shared_markers",
                ),
                publicTables(dataSource),
            )
        } finally {
            dataSource.close()
        }
        assertTrue(dataSource.isClosed)
    }

    @Test
    fun `broken migration fails without being silenced`() {
        val dataSource = createDataSource()
        val schema = "broken_${UUID.randomUUID().toString().replace("-", "")}"
        val migrationDirectory = tempDirectory.resolve("broken-migrations")
        Files.createDirectories(migrationDirectory)
        Files.writeString(migrationDirectory.resolve("V1__broken.sql"), "THIS IS NOT VALID SQL;")

        try {
            assertFailsWith<FlywayException> {
                FlywayMigrator(
                    dataSource = dataSource,
                    locations = arrayOf("filesystem:${migrationDirectory.toAbsolutePath()}"),
                    schemas = arrayOf(schema),
                ).migrateAndValidate()
            }
        } finally {
            dataSource.close()
        }
        assertTrue(dataSource.isClosed)
    }

    private fun createDataSource() = DatabaseConfig(
        url = postgres.jdbcUrl,
        user = postgres.username,
        password = SecretValue.from(postgres.password),
    ).let { config ->
        try {
            DatabaseFactory.create(config).also(DatabaseFactory::validateConnectivity)
        } finally {
            config.password.close()
        }
    }

    private fun publicTables(dataSource: javax.sql.DataSource): Set<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildSet {
                        while (result.next()) add(result.getString(1))
                    }
                }
            }
        }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("eve_shared_map_test")
            .withUsername("eve_shared_map_test")
            .withPassword("test-only-password")
    }
}
