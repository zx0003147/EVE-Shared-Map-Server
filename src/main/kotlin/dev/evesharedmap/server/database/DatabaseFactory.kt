package dev.evesharedmap.server.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.evesharedmap.server.config.DatabaseConfig
import java.sql.Connection

object DatabaseFactory {
    private const val CONNECTION_TIMEOUT_MS = 5_000L
    private const val VALIDATION_TIMEOUT_MS = 2_000L

    fun create(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password.copyToString()
            maximumPoolSize = 10
            minimumIdle = 1
            connectionTimeout = CONNECTION_TIMEOUT_MS
            validationTimeout = VALIDATION_TIMEOUT_MS
            initializationFailTimeout = CONNECTION_TIMEOUT_MS
            poolName = "eve-shared-map-pool"
            addDataSourceProperty("tcpKeepAlive", "true")
        }
        return HikariDataSource(hikariConfig)
    }

    fun validateConnectivity(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.prepareHealthStatement().use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getInt(1) == 1) { "Database readiness validation failed." }
                }
            }
        }
    }
}

internal fun Connection.prepareHealthStatement() = prepareStatement("SELECT 1").apply {
    queryTimeout = 2
}
