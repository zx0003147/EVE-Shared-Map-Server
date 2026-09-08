package dev.evesharedmap.server

import dev.evesharedmap.server.config.ConfigurationException
import dev.evesharedmap.server.config.ServerConfig
import dev.evesharedmap.server.database.DatabaseFactory
import dev.evesharedmap.server.database.FlywayMigrator
import dev.evesharedmap.server.health.DatabaseReadiness
import dev.evesharedmap.server.http.configureHttp
import dev.evesharedmap.server.logging.logSanitizedError
import dev.evesharedmap.server.marker.SharedMarkerService
import dev.evesharedmap.server.marker.SharedMarkerValidation
import dev.evesharedmap.server.security.CredentialHasher
import dev.evesharedmap.server.service.ServiceException
import dev.evesharedmap.server.service.SharedMapService
import dev.evesharedmap.server.universe.SolarSystemAllowlist
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.time.Duration
import kotlin.system.exitProcess

private const val SHUTDOWN_GRACE_PERIOD_MS = 5_000L
private const val SHUTDOWN_TIMEOUT_MS = 10_000L

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        val exitCode = if (args.first() == "bootstrap-admin") {
            runBootstrapAdmin(args.drop(1).toTypedArray(), System.getenv(), System.out, System.err)
        } else {
            System.err.println("Unknown command. Expected bootstrap-admin or no command for server mode.")
            2
        }
        if (exitCode != 0) exitProcess(exitCode)
        return
    }
    runServer()
}

internal fun runBootstrapAdmin(
    args: Array<String>,
    environment: Map<String, String>,
    out: PrintStream,
    errorOut: PrintStream,
): Int {
    val options = try {
        BootstrapOptions.parse(args)
    } catch (error: IllegalArgumentException) {
        errorOut.println(error.message)
        errorOut.println(BootstrapOptions.USAGE)
        return 2
    }

    return try {
        withApplicationServices(environment) { _, service, _, _ ->
            val result = service.bootstrapAdmin(options.displayName, options.workspaceName, options.inviteLifetime)
            out.println("Bootstrap completed.")
            out.println("userId=${result.userId}")
            out.println("workspaceId=${result.workspaceId}")
            out.println("memberId=${result.memberId}")
            out.println("inviteId=${result.inviteId}")
            out.println("inviteExpiresAt=${result.inviteExpiresAt}")
            out.println("inviteToken=${result.rawInviteSecret}")
            out.println("Store and deliver the invite securely; it cannot be shown again.")
        }
        0
    } catch (error: ConfigurationException) {
        errorOut.println("Bootstrap failed: ${error.message}")
        1
    } catch (error: ServiceException) {
        errorOut.println("Bootstrap failed: ${error.safeMessage}")
        1
    } catch (_: Throwable) {
        errorOut.println("Bootstrap failed: the server could not complete the operation.")
        1
    }
}

private fun runServer() {
    val logger = LoggerFactory.getLogger("server.lifecycle")
    var runtime: ServerRuntime? = null

    try {
        logger.info("event=startup stage=configuration")
        withApplicationServices(System.getenv()) { resources, service, markerService, migration ->
            val config = resources.config
            val dataSource = resources.dataSource
            logger.info("event=startup stage=database_validation")
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
                    sharedMapService = service,
                    sharedMarkerService = markerService,
                    universeBuild = markerService.universeBuild,
                    allowedOrigins = config.allowedOrigins,
                )
            }

            runtime = ServerRuntime(
                startServer = { wait -> engine.start(wait = wait) },
                stopServer = { engine.stop(SHUTDOWN_GRACE_PERIOD_MS, SHUTDOWN_TIMEOUT_MS) },
                databaseResource = AutoCloseable {},
            )
            Runtime.getRuntime().addShutdownHook(Thread({ runtime.close() }, "shared-map-shutdown"))
            logger.info("event=startup stage=listener_ready")
            runtime.start(wait = true)
        }
    } catch (error: Throwable) {
        logger.logSanitizedError("startup_failed", error)
        exitProcess(1)
    } finally {
        runtime?.close()
    }
}

private inline fun <T> withApplicationServices(
    environment: Map<String, String>,
    block: (
        ApplicationResources,
        SharedMapService,
        SharedMarkerService,
        dev.evesharedmap.server.database.MigrationSummary,
    ) -> T,
): T {
    val config = ServerConfig.load(environment)
    val hasher = try {
        CredentialHasher(config.tokenPepper)
    } finally {
        config.tokenPepper.close()
    }
    val dataSource = try {
        DatabaseFactory.create(config.database)
    } catch (error: Throwable) {
        hasher.close()
        throw error
    } finally {
        config.database.password.close()
    }
    val resources = ApplicationResources(config, dataSource, hasher)
    return try {
        DatabaseFactory.validateConnectivity(dataSource)
        val migration = FlywayMigrator(dataSource).migrateAndValidate()
        val allowlist = SolarSystemAllowlist.load()
        block(
            resources,
            SharedMapService(dataSource, hasher),
            SharedMarkerService(dataSource, SharedMarkerValidation(allowlist)),
            migration,
        )
    } finally {
        resources.close()
    }
}

private data class ApplicationResources(
    val config: ServerConfig,
    val dataSource: com.zaxxer.hikari.HikariDataSource,
    val hasher: CredentialHasher,
) : AutoCloseable {
    override fun close() {
        try {
            dataSource.close()
        } finally {
            hasher.close()
        }
    }
}

private data class BootstrapOptions(
    val displayName: String,
    val workspaceName: String,
    val inviteLifetime: Duration,
) {
    companion object {
        const val USAGE =
            "Usage: bootstrap-admin --display-name <name> --workspace-name <name> [--invite-ttl <1h..30d>]"

        fun parse(args: Array<String>): BootstrapOptions {
            val values = linkedMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val key = args[index]
                if (key !in setOf("--display-name", "--workspace-name", "--invite-ttl")) {
                    throw IllegalArgumentException("Unknown bootstrap option: $key")
                }
                if (values.containsKey(key)) throw IllegalArgumentException("Duplicate bootstrap option: $key")
                val value = args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
                    ?: throw IllegalArgumentException("Missing value for bootstrap option: $key")
                values[key] = value
                index += 2
            }
            val displayName = values["--display-name"]
                ?: throw IllegalArgumentException("--display-name is required.")
            val workspaceName = values["--workspace-name"]
                ?: throw IllegalArgumentException("--workspace-name is required.")
            return BootstrapOptions(
                displayName,
                workspaceName,
                parseDuration(values["--invite-ttl"] ?: "1h"),
            )
        }

        private fun parseDuration(raw: String): Duration {
            val match = Regex("([1-9][0-9]*)([hd])").matchEntire(raw)
                ?: throw IllegalArgumentException("--invite-ttl must use hours or days, for example 1h or 3d.")
            val amount = match.groupValues[1].toLongOrNull()
                ?: throw IllegalArgumentException("--invite-ttl is outside the supported range.")
            return try {
                when (match.groupValues[2]) {
                    "h" -> Duration.ofHours(amount)
                    else -> Duration.ofDays(amount)
                }
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("--invite-ttl is outside the supported range.")
            }
        }
    }
}
