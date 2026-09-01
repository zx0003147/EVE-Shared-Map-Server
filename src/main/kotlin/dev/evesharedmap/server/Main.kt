package dev.evesharedmap.server

import dev.evesharedmap.server.config.ServerConfig
import dev.evesharedmap.server.database.DatabaseFactory
import dev.evesharedmap.server.database.FlywayMigrator
import dev.evesharedmap.server.health.DatabaseReadiness
import dev.evesharedmap.server.http.configureHttp
import dev.evesharedmap.server.logging.logSanitizedError
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val SHUTDOWN_GRACE_PERIOD_MS = 5_000L
private const val SHUTDOWN_TIMEOUT_MS = 10_000L

fun main() {
    val logger = LoggerFactory.getLogger("server.lifecycle")
    var runtime: ServerRuntime? = null

    try {
        logger.info("event=startup stage=configuration")
        val config = ServerConfig.load()
        val dataSource = try {
            DatabaseFactory.create(config.database)
        } finally {
            config.database.password.close()
        }

        try {
            logger.info("event=startup stage=database_validation")
            DatabaseFactory.validateConnectivity(dataSource)
            val migration = FlywayMigrator(dataSource).migrateAndValidate()
            logger.info(
                "event=migration result=success executed={} schemaVersion={}",
                migration.migrationsExecuted,
                migration.currentVersion ?: "none",
            )

            val engine = embeddedServer(
                factory = CIO,
                host = config.bindHost,
                port = config.port,
            ) {
                configureHttp(
                    readinessProbe = DatabaseReadiness(dataSource),
                    serverVersion = BuildInfo.serverVersion,
                )
            }

            runtime = ServerRuntime(
                startServer = { wait -> engine.start(wait = wait) },
                stopServer = { engine.stop(SHUTDOWN_GRACE_PERIOD_MS, SHUTDOWN_TIMEOUT_MS) },
                databaseResource = dataSource,
            )
            Runtime.getRuntime().addShutdownHook(Thread({ runtime.close() }, "shared-map-shutdown"))
            logger.info("event=startup stage=listener_ready")
            runtime.start(wait = true)
        } catch (error: Throwable) {
            dataSource.close()
            throw error
        }
    } catch (error: Throwable) {
        logger.logSanitizedError("startup_failed", error)
        exitProcess(1)
    } finally {
        runtime?.close()
    }
}
