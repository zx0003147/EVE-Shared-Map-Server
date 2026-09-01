package dev.evesharedmap.server.database

import org.flywaydb.core.Flyway
import javax.sql.DataSource

data class MigrationSummary(
    val migrationsExecuted: Int,
    val currentVersion: String?,
)

class FlywayMigrator(
    private val dataSource: DataSource,
    private val locations: Array<String> = arrayOf("classpath:db/migration"),
    private val schemas: Array<String> = emptyArray(),
) {
    fun migrateAndValidate(): MigrationSummary {
        val configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations(*locations)
            .cleanDisabled(true)
            .validateMigrationNaming(true)

        if (schemas.isNotEmpty()) {
            configuration.schemas(*schemas).defaultSchema(schemas.first())
        }

        val flyway = configuration.load()
        val result = flyway.migrate()
        flyway.validate()
        return MigrationSummary(
            migrationsExecuted = result.migrationsExecuted,
            currentVersion = flyway.info().current()?.version?.version,
        )
    }
}
