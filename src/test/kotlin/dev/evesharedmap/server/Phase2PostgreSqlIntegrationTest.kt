package dev.evesharedmap.server

import com.zaxxer.hikari.HikariDataSource
import dev.evesharedmap.server.api.PROTOCOL_JSON
import dev.evesharedmap.server.config.DatabaseConfig
import dev.evesharedmap.server.config.SecretValue
import dev.evesharedmap.server.database.DatabaseFactory
import dev.evesharedmap.server.database.FlywayMigrator
import dev.evesharedmap.server.domain.WorkspaceRole
import dev.evesharedmap.server.domain.WorkspaceCapability
import dev.evesharedmap.server.health.ReadinessProbe
import dev.evesharedmap.server.http.configureHttp
import dev.evesharedmap.server.security.CredentialGenerator
import dev.evesharedmap.server.security.CredentialHasher
import dev.evesharedmap.server.security.CredentialKind
import dev.evesharedmap.server.security.InMemoryTokenBucketRateLimiter
import dev.evesharedmap.server.security.SecureCredentialGenerator
import dev.evesharedmap.server.service.MutationResponse
import dev.evesharedmap.server.service.ServiceException
import dev.evesharedmap.server.service.SharedMapService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class Phase2PostgreSqlIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `Phase 1 schema upgrades through Phase 3 exactly once`() {
        val bundle = newBundle(migrate = false)
        bundle.use {
            val v1Directory = tempDirectory.resolve("v1")
            Files.createDirectories(v1Directory)
            Files.writeString(v1Directory.resolve("V1__skeleton.sql"), "SELECT 1;\n")
            val phase1 = Flyway.configure()
                .dataSource(bundle.dataSource)
                .schemas(bundle.schema)
                .defaultSchema(bundle.schema)
                .locations("filesystem:${v1Directory.toAbsolutePath()}")
                .load()
            assertEquals(1, phase1.migrate().migrationsExecuted)

            val upgrade = FlywayMigrator(bundle.dataSource, schemas = arrayOf(bundle.schema)).migrateAndValidate()
            val repeat = FlywayMigrator(bundle.dataSource, schemas = arrayOf(bundle.schema)).migrateAndValidate()

            assertEquals(2, upgrade.migrationsExecuted)
            assertEquals("3", upgrade.currentVersion)
            assertEquals(0, repeat.migrationsExecuted)
            assertEquals(8, bundle.businessTables().size)
        }
    }

    @Test
    fun `bootstrap is atomic one-shot and stores no raw invite`() {
        val generator = QueueCredentialGenerator(invite('A'))
        newBundle(generator).use { bundle ->
            val result = bundle.service.bootstrapAdmin("  Coord_A  ", "  Alliance Map  ", Duration.ofHours(1))

            assertEquals("Coord_A", bundle.scalar("SELECT display_name FROM users"))
            assertEquals("Alliance Map", bundle.scalar("SELECT name FROM workspaces"))
            assertEquals("ADMIN", bundle.scalar("SELECT role FROM workspace_members"))
            assertEquals(1L, bundle.count("invites"))
            assertEquals(2L, bundle.count("audit_events"))
            assertFalse(bundle.databaseTextDump().contains(result.rawInviteSecret))

            val repeated = assertFailsWith<ServiceException> {
                bundle.service.bootstrapAdmin("Other", "Other", Duration.ofHours(1))
            }
            assertEquals("BOOTSTRAP_ALREADY_COMPLETED", repeated.code)
            assertEquals(1L, bundle.count("workspaces"))
        }
    }

    @Test
    fun `bootstrap CLI succeeds once with safe controlled repeat failure`() {
        val schema = "cli_${UUID.randomUUID().toString().replace("-", "")}"
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val passwordFile = tempDirectory.resolve("cli-db-password.txt")
        val pepperFile = tempDirectory.resolve("cli-token-pepper.txt")
        Files.writeString(passwordFile, postgres.password)
        Files.writeString(pepperFile, "cli-test-pepper-with-sufficient-random-material")
        val separator = if (postgres.jdbcUrl.contains('?')) '&' else '?'
        val environment = mapOf(
            "SHARED_MAP_DATABASE_URL" to "${postgres.jdbcUrl}${separator}currentSchema=$schema",
            "SHARED_MAP_DATABASE_USER" to postgres.username,
            "SHARED_MAP_DATABASE_PASSWORD_FILE" to passwordFile.toString(),
            "SHARED_MAP_TOKEN_PEPPER_FILE" to pepperFile.toString(),
        )
        val firstOut = ByteArrayOutputStream()
        val firstError = ByteArrayOutputStream()
        val args = arrayOf(
            "--display-name", "CLI Admin",
            "--workspace-name", "CLI Workspace",
            "--invite-ttl", "1h",
        )

        val first = runBootstrapAdmin(args, environment, PrintStream(firstOut), PrintStream(firstError))
        val secondError = ByteArrayOutputStream()
        val second = runBootstrapAdmin(args, environment, PrintStream(ByteArrayOutputStream()), PrintStream(secondError))

        assertEquals(0, first)
        assertContains(firstOut.toString(), "inviteToken=esm_inv_")
        assertEquals("", firstError.toString())
        assertEquals(1, second)
        assertContains(secondError.toString(), "Bootstrap failed:")
        assertFalse(secondError.toString().contains("Exception"))
        assertFalse(secondError.toString().contains(postgres.password))
    }

    @Test
    fun `concurrent invite exchange issues exactly one device token`() {
        val rawInvite = invite('A')
        val generator = QueueCredentialGenerator(rawInvite, device('B'), device('C'))
        newBundle(generator).use { bundle ->
            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            val outcomes = runBlocking {
                listOf(
                    async { runCatching { bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Device One", "r1") } },
                    async { runCatching { bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Device Two", "r2") } },
                ).awaitAll()
            }

            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { (it.exceptionOrNull() as? ServiceException)?.code == "INVITE_ALREADY_USED" })
            assertEquals(1L, bundle.count("access_tokens"))
            assertNotNull(bundle.scalarOrNull("SELECT used_at::text FROM invites"))
        }
    }

    @Test
    fun `ordinary mutation replays response while invite creation conflicts without duplicate or secret storage`() {
        val generator = QueueCredentialGenerator(invite('A'), device('B'), invite('C'), invite('D'))
        newBundle(generator).use { bundle ->
            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            val admin = bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Admin Device", "exchange").principal
            val memberKey = UUID.randomUUID()
            val memberFingerprint = ByteArray(32) { 1 }
            val memberOperation: (java.sql.Connection) -> MutationResponse = { connection ->
                val member = bundle.service.createMember(
                    connection,
                    admin,
                    "Editor",
                    WorkspaceRole.EDITOR,
                    "member-request",
                )
                MutationResponse(
                    201,
                    buildJsonObject {
                        put("memberId", member.memberId.toString())
                        put("version", member.version)
                    },
                )
            }
            val firstMember = bundle.service.executeIdempotent(
                admin.tokenId,
                memberKey,
                memberFingerprint,
                WorkspaceCapability.ADMIN,
                operation = memberOperation,
            )
            val replayedMember = bundle.service.executeIdempotent(
                admin.tokenId,
                memberKey,
                memberFingerprint,
                WorkspaceCapability.ADMIN,
                operation = memberOperation,
            )
            assertFalse(firstMember.replayed)
            assertTrue(replayedMember.replayed)
            assertEquals(firstMember.response.responseBody, replayedMember.response.responseBody)
            assertEquals(2L, bundle.count("workspace_members"))

            val targetMemberId = UUID.fromString(
                (firstMember.response.responseBody as JsonObject)["memberId"]!!.jsonPrimitive.content,
            )
            val inviteKey = UUID.randomUUID()
            val inviteFingerprint = ByteArray(32) { 2 }
            val rawSecrets = ConcurrentLinkedQueue<String>()
            val outcomes = runBlocking {
                List(2) { index ->
                    async {
                        runCatching {
                            bundle.service.executeIdempotent(
                                admin.tokenId,
                                inviteKey,
                                inviteFingerprint,
                                WorkspaceCapability.ADMIN,
                                nonReplayableSecretResponse = true,
                            ) { connection ->
                                val issued = bundle.service.createInvite(
                                    connection,
                                    admin,
                                    targetMemberId,
                                    Duration.ofHours(72),
                                    "invite-$index",
                                )
                                rawSecrets += issued.rawSecret
                                val safe = buildJsonObject {
                                    put("inviteId", issued.metadata.inviteId.toString())
                                    put("workspaceId", issued.metadata.workspaceId.toString())
                                    put("memberId", issued.metadata.memberId.toString())
                                    put("userId", issued.metadata.userId.toString())
                                    put("expiresAt", issued.metadata.expiresAt.toString())
                                    put("status", issued.metadata.status.name)
                                }
                                MutationResponse(
                                    201,
                                    buildJsonObject {
                                        put("inviteId", issued.metadata.inviteId.toString())
                                        put("inviteToken", issued.rawSecret)
                                    },
                                    storageBody = safe,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            val conflict = outcomes.mapNotNull { it.exceptionOrNull() as? ServiceException }.single()
            assertEquals("IDEMPOTENCY_RESPONSE_NOT_REPLAYABLE", conflict.code)
            assertEquals(409, conflict.status)
            assertEquals(1, rawSecrets.size)
            assertEquals(2L, bundle.count("invites")) // used bootstrap invite plus exactly one Editor invite
            assertFalse(bundle.databaseTextDump().contains(rawSecrets.single()))
            assertContains(conflict.details.toString(), targetMemberId.toString())
        }
    }

    @Test
    fun `real PostgreSQL HTTP flow enforces live roles membership and device revocation`() = testApplication {
        val bundle = newBundle(SecureCredentialGenerator())
        try {
            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            val adminIssued = bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Admin Laptop", "bootstrap-exchange")
            val adminToken = adminIssued.rawSecret
            val workspaceId = adminIssued.principal.membership.workspaceId

            application {
                configureHttp(
                    readinessProbe = ReadinessProbe { true },
                    serverVersion = "0.2.0-test",
                    sharedMapService = bundle.service,
                    rateLimiter = InMemoryTokenBucketRateLimiter(),
                )
            }

            val createMember = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(adminToken)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Editor","role":"EDITOR"}""")
            }
            assertEquals(HttpStatusCode.Created, createMember.status)
            val memberJson = PROTOCOL_JSON.parseToJsonElement(createMember.bodyAsText()).jsonObject
            val editorMemberId = memberJson["memberId"]!!.jsonPrimitive.content

            val createInvite = client.post("/api/v1/workspaces/$workspaceId/members/$editorMemberId/invites") {
                bearer(adminToken)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"expiresInHours":72}""")
            }
            assertEquals(HttpStatusCode.Created, createInvite.status)
            val inviteToken = PROTOCOL_JSON.parseToJsonElement(createInvite.bodyAsText())
                .jsonObject["inviteToken"]!!.jsonPrimitive.content

            val exchange = client.post("/api/v1/auth/exchange-invite") {
                contentType(ContentType.Application.Json)
                setBody("""{"inviteToken":"$inviteToken","deviceName":"Editor PC"}""")
            }
            assertEquals(HttpStatusCode.Created, exchange.status)
            val exchangeJson = PROTOCOL_JSON.parseToJsonElement(exchange.bodyAsText()).jsonObject
            val editorToken = exchangeJson["accessToken"]!!.jsonPrimitive.content
            val editorTokenId = exchangeJson["tokenId"]!!.jsonPrimitive.content

            val secondInviteResponse = client.post(
                "/api/v1/workspaces/$workspaceId/members/$editorMemberId/invites",
            ) {
                bearer(adminToken)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"expiresInHours":72}""")
            }
            val secondInviteToken = PROTOCOL_JSON.parseToJsonElement(secondInviteResponse.bodyAsText())
                .jsonObject["inviteToken"]!!.jsonPrimitive.content
            val secondExchange = client.post("/api/v1/auth/exchange-invite") {
                contentType(ContentType.Application.Json)
                setBody("""{"inviteToken":"$secondInviteToken","deviceName":"Editor Backup"}""")
            }
            val secondExchangeJson = PROTOCOL_JSON.parseToJsonElement(secondExchange.bodyAsText()).jsonObject
            val secondEditorToken = secondExchangeJson["accessToken"]!!.jsonPrimitive.content
            val secondEditorTokenId = secondExchangeJson["tokenId"]!!.jsonPrimitive.content
            val memberDevices = client.get(
                "/api/v1/workspaces/$workspaceId/members/$editorMemberId/devices",
            ) { bearer(adminToken) }
            assertEquals(HttpStatusCode.OK, memberDevices.status)
            assertContains(memberDevices.bodyAsText(), secondEditorTokenId)
            val adminRevokeDevice = client.delete(
                "/api/v1/workspaces/$workspaceId/members/$editorMemberId/devices/$secondEditorTokenId",
            ) {
                bearer(adminToken)
                idempotency()
            }
            assertEquals(HttpStatusCode.NoContent, adminRevokeDevice.status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/me") { bearer(secondEditorToken) }.status,
            )

            assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearer(editorToken) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/workspaces/$workspaceId") { bearer(editorToken) }.status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/v1/workspaces/$workspaceId/members") { bearer(editorToken) }.status,
            )

            val roleChange = client.patch("/api/v1/workspaces/$workspaceId/members/$editorMemberId") {
                bearer(adminToken)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"expectedVersion":1,"role":"VIEWER"}""")
            }
            assertEquals(HttpStatusCode.OK, roleChange.status)
            val meAfterRoleChange = client.get("/api/v1/me") { bearer(editorToken) }.bodyAsText()
            assertContains(meAfterRoleChange, "\"role\":\"VIEWER\"")

            val remove = client.delete(
                "/api/v1/workspaces/$workspaceId/members/$editorMemberId?expectedVersion=2",
            ) {
                bearer(adminToken)
                idempotency()
            }
            assertEquals(HttpStatusCode.NoContent, remove.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearer(editorToken) }.status)

            val adminDevices = client.get("/api/v1/me/devices") { bearer(adminToken) }
            assertEquals(HttpStatusCode.OK, adminDevices.status)
            assertFalse(adminDevices.bodyAsText().contains(adminToken))
            assertNotEquals(editorTokenId, adminIssued.principal.tokenId.toString())

            val revokeCurrent = client.delete("/api/v1/me/devices/${adminIssued.principal.tokenId}") {
                bearer(adminToken)
                idempotency()
            }
            assertEquals(HttpStatusCode.NoContent, revokeCurrent.status)
            val afterRevoke = client.get("/api/v1/me") { bearer(adminToken) }
            assertEquals(HttpStatusCode.Unauthorized, afterRevoke.status)
            assertContains(afterRevoke.bodyAsText(), "TOKEN_REVOKED")
            assertFalse(bundle.databaseTextDump().contains(adminToken))
            assertFalse(bundle.databaseTextDump().contains(inviteToken))
        } finally {
            bundle.close()
        }
    }

    @Test
    fun `invite idempotency HTTP conflict exposes exact safe metadata and creates one row`() = testApplication {
        val bundle = newBundle(SecureCredentialGenerator())
        try {
            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            val admin = bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Admin", "exchange")
            val workspaceId = admin.principal.membership.workspaceId
            application {
                configureHttp(
                    ReadinessProbe { true },
                    "test",
                    sharedMapService = bundle.service,
                    rateLimiter = InMemoryTokenBucketRateLimiter(),
                )
            }
            val memberResponse = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Invite Target","role":"VIEWER"}""")
            }
            val member = PROTOCOL_JSON.parseToJsonElement(memberResponse.bodyAsText()).jsonObject
            val memberId = member["memberId"]!!.jsonPrimitive.content
            val key = UUID.randomUUID().toString()
            val url = "/api/v1/workspaces/$workspaceId/members/$memberId/invites"

            val first = client.post(url) {
                bearer(admin.rawSecret)
                header("Idempotency-Key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"expiresInHours":72}""")
            }
            val rawSecret = PROTOCOL_JSON.parseToJsonElement(first.bodyAsText())
                .jsonObject["inviteToken"]!!.jsonPrimitive.content
            val second = client.post(url) {
                bearer(admin.rawSecret)
                header("Idempotency-Key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"expiresInHours":72}""")
            }
            val conflict = PROTOCOL_JSON.parseToJsonElement(second.bodyAsText()).jsonObject
            val details = conflict["details"]!!.jsonObject

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals("IDEMPOTENCY_RESPONSE_NOT_REPLAYABLE", conflict["code"]!!.jsonPrimitive.content)
            assertEquals(
                setOf("inviteId", "workspaceId", "memberId", "userId", "displayName", "role", "expiresAt", "status"),
                details.keys,
            )
            assertEquals(memberId, details["memberId"]!!.jsonPrimitive.content)
            assertFalse(second.bodyAsText().contains(rawSecret))
            assertFalse(second.bodyAsText().contains("hash", ignoreCase = true))
            assertEquals(1L, bundle.countWhere("invites", "member_id = '$memberId'::uuid"))
            assertFalse(bundle.databaseTextDump().contains(rawSecret))

            val inviteId = details["inviteId"]!!.jsonPrimitive.content
            val inviteList = client.get("/api/v1/workspaces/$workspaceId/invites") {
                bearer(admin.rawSecret)
            }
            assertEquals(HttpStatusCode.OK, inviteList.status)
            assertContains(inviteList.bodyAsText(), inviteId)
            assertFalse(inviteList.bodyAsText().contains(rawSecret))
            val revoke = client.delete("/api/v1/workspaces/$workspaceId/invites/$inviteId") {
                bearer(admin.rawSecret)
                idempotency()
            }
            assertEquals(HttpStatusCode.NoContent, revoke.status)
            val revokedExchange = client.post("/api/v1/auth/exchange-invite") {
                contentType(ContentType.Application.Json)
                setBody("""{"inviteToken":"$rawSecret","deviceName":"Revoked"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, revokedExchange.status)
            assertContains(revokedExchange.bodyAsText(), "INVITE_REVOKED")
        } finally {
            bundle.close()
        }
    }

    @Test
    fun `credential failure states and final Admin invariant use frozen errors`() {
        val clock = MutableClock(Instant.parse("2026-09-01T00:00:00Z"))
        val generator = QueueCredentialGenerator(invite('A'), device('B'))
        newBundle(generator, clock = clock).use { bundle ->
            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            assertEquals(
                "UNAUTHENTICATED",
                assertFailsWith<ServiceException> { bundle.service.authenticate("not-a-token") }.code,
            )
            assertEquals(
                "UNAUTHENTICATED",
                assertFailsWith<ServiceException> { bundle.service.authenticate(device('Z')) }.code,
            )
            val admin = bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Admin", "exchange").principal
            val downgrade = assertFailsWith<ServiceException> {
                bundle.service.executeIdempotent(
                    admin.tokenId,
                    UUID.randomUUID(),
                    ByteArray(32) { 9 },
                    WorkspaceCapability.ADMIN,
                ) { connection ->
                    bundle.service.updateMember(
                        connection,
                        admin,
                        admin.membership.memberId,
                        1,
                        null,
                        WorkspaceRole.VIEWER,
                        "downgrade",
                    )
                    MutationResponse(204, null)
                }
            }
            assertEquals("LAST_ADMIN_REQUIRED", downgrade.code)

            clock.now = clock.now.plus(Duration.ofDays(91))
            assertEquals(
                "TOKEN_EXPIRED",
                assertFailsWith<ServiceException> { bundle.service.authenticate(device('B')) }.code,
            )
        }

        val expiredClock = MutableClock(Instant.parse("2026-09-01T00:00:00Z"))
        newBundle(QueueCredentialGenerator(invite('C')), clock = expiredClock).use { bundle ->
            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            expiredClock.now = expiredClock.now.plus(Duration.ofHours(2))
            assertEquals(
                "INVITE_EXPIRED",
                assertFailsWith<ServiceException> {
                    bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Late", "expired")
                }.code,
            )
        }
    }

    @Test
    fun `database constraints and concurrent role changes preserve one active Admin`() {
        newBundle(SecureCredentialGenerator()).use { bundle ->
            val bootstrap = bundle.service.bootstrapAdmin("Admin One", "Workspace", Duration.ofHours(1))
            val actor = bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Admin", "exchange").principal
            val second = bundle.service.executeIdempotent(
                actor.tokenId,
                UUID.randomUUID(),
                ByteArray(32) { 7 },
                WorkspaceCapability.ADMIN,
            ) { connection ->
                val member = bundle.service.createMember(
                    connection,
                    actor,
                    "Admin Two",
                    WorkspaceRole.ADMIN,
                    "create-second-admin",
                )
                MutationResponse(201, buildJsonObject { put("memberId", member.memberId.toString()) })
            }
            val secondId = UUID.fromString(
                (second.response.responseBody as JsonObject)["memberId"]!!.jsonPrimitive.content,
            )

            val outcomes = runBlocking {
                listOf(actor.membership.memberId, secondId).mapIndexed { index, target ->
                    async {
                        runCatching {
                            bundle.service.executeIdempotent(
                                actor.tokenId,
                                UUID.randomUUID(),
                                ByteArray(32) { (index + 11).toByte() },
                                WorkspaceCapability.ADMIN,
                            ) { connection ->
                                bundle.service.updateMember(
                                    connection,
                                    actor,
                                    target,
                                    1,
                                    null,
                                    WorkspaceRole.VIEWER,
                                    "concurrent-$index",
                                )
                                MutationResponse(204, null)
                            }
                        }
                    }
                }.awaitAll()
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1L, bundle.countWhere("workspace_members", "role = 'ADMIN' AND revoked_at IS NULL"))

            assertFailsWith<SQLException> {
                bundle.dataSource.connection.use { connection ->
                    connection.createStatement().use { it.executeUpdate("UPDATE workspace_members SET role = 'OWNER'") }
                }
            }
            assertFailsWith<SQLException> {
                bundle.dataSource.connection.use { connection ->
                    connection.createStatement().use { it.executeUpdate("UPDATE audit_events SET action = 'TAMPERED'") }
                }
            }
            assertTrue(bundle.businessTables().contains("shared_markers"))
        }
    }

    @Test
    fun `invite exchange enforces brute force rate limit`() = testApplication {
        val bundle = newBundle(SecureCredentialGenerator())
        try {
            application {
                configureHttp(
                    ReadinessProbe { true },
                    "test",
                    sharedMapService = bundle.service,
                    rateLimiter = InMemoryTokenBucketRateLimiter(),
                )
            }
            val statuses = List(6) {
                client.post("/api/v1/auth/exchange-invite") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"inviteToken":"${invite('Z')}","deviceName":"Device"}""")
                }
            }
            assertEquals(List(5) { HttpStatusCode.Unauthorized }, statuses.take(5).map { it.status })
            assertEquals(HttpStatusCode.TooManyRequests, statuses.last().status)
            assertContains(statuses.last().bodyAsText(), "RATE_LIMITED")
            assertNotNull(statuses.last().headers[HttpHeaders.RetryAfter])
        } finally {
            bundle.close()
        }
    }

    @Test
    fun `invite exchange enforces 4 KiB body limit`() = testApplication {
        val bundle = newBundle(SecureCredentialGenerator())
        try {
            application {
                configureHttp(
                    ReadinessProbe { true },
                    "test",
                    sharedMapService = bundle.service,
                    rateLimiter = object : dev.evesharedmap.server.security.RateLimiter {
                        override fun consume(key: String, capacity: Int, period: Duration) =
                            dev.evesharedmap.server.security.RateLimitDecision(true)
                    },
                )
            }
            val oversized = client.post("/api/v1/auth/exchange-invite") {
                contentType(ContentType.Application.Json)
                setBody("""{"inviteToken":"${"x".repeat(5000)}","deviceName":"Device"}""")
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            assertContains(oversized.bodyAsText(), "PAYLOAD_TOO_LARGE")
        } finally {
            bundle.close()
        }
    }

    @Test
    fun `HTTP security rejects forged authority malicious input and unsafe idempotency reuse`() = testApplication {
        val bundle = newBundle(SecureCredentialGenerator())
        try {
            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            val admin = bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Admin", "exchange")
            val workspaceId = admin.principal.membership.workspaceId
            application {
                configureHttp(
                    ReadinessProbe { true },
                    "test",
                    sharedMapService = bundle.service,
                    rateLimiter = InMemoryTokenBucketRateLimiter(),
                )
            }

            listOf(null, "Basic abc", "Bearer ${device('Z')}").forEach { authorization ->
                val response = client.get("/api/v1/me") {
                    if (authorization != null) header(HttpHeaders.Authorization, authorization)
                }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertContains(response.bodyAsText(), "UNAUTHENTICATED")
                assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
            }

            val hiddenWorkspace = client.get("/api/v1/workspaces/${UUID.randomUUID()}") { bearer(admin.rawSecret) }
            assertEquals(HttpStatusCode.NotFound, hiddenWorkspace.status)

            val forbiddenRole = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Forged","role":"OWNER"}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, forbiddenRole.status)

            val forgedWorkspaceField = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Forged","role":"ADMIN","workspaceId":"${UUID.randomUUID()}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, forgedWorkspaceField.status)

            val controlCharacter = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"bad\u0000name","role":"VIEWER"}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, controlCharacter.status)

            val injection = "Robert'); DROP TABLE users;--"
            val idempotencyKey = UUID.randomUUID().toString()
            val injectionRequest = """{"displayName":"$injection","role":"VIEWER"}"""
            val created = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                header("Idempotency-Key", idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(injectionRequest)
            }
            val replayed = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                header("Idempotency-Key", idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(injectionRequest)
            }
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(
                PROTOCOL_JSON.parseToJsonElement(created.bodyAsText()),
                PROTOCOL_JSON.parseToJsonElement(replayed.bodyAsText()),
            )
            assertEquals(injection, bundle.scalar("SELECT display_name FROM users WHERE display_name LIKE 'Robert%'") )

            val keyReuse = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                header("Idempotency-Key", idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Different","role":"VIEWER"}""")
            }
            assertEquals(HttpStatusCode.Conflict, keyReuse.status)
            assertContains(keyReuse.bodyAsText(), "IDEMPOTENCY_KEY_REUSED")

            val missingKey = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Missing Key","role":"VIEWER"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, missingKey.status)
            assertContains(missingKey.bodyAsText(), "IDEMPOTENCY_KEY_REQUIRED")

            val oversized = client.post("/api/v1/workspaces/$workspaceId/members") {
                bearer(admin.rawSecret)
                idempotency()
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"${"x".repeat(33 * 1024)}","role":"VIEWER"}""")
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            assertEquals(1L, bundle.countWhere("users", "display_name LIKE 'Robert%'"))
        } finally {
            bundle.close()
        }
    }

    private fun newBundle(
        generator: CredentialGenerator = SecureCredentialGenerator(),
        migrate: Boolean = true,
        clock: Clock = Clock.systemUTC(),
    ): TestBundle {
        val schema = "phase2_${UUID.randomUUID().toString().replace("-", "")}"
        val separator = if (postgres.jdbcUrl.contains('?')) '&' else '?'
        val config = DatabaseConfig(
            url = "${postgres.jdbcUrl}${separator}currentSchema=$schema",
            user = postgres.username,
            password = SecretValue.from(postgres.password),
        )
        val dataSource = try {
            DatabaseFactory.create(config)
        } finally {
            config.password.close()
        }
        val pepper = SecretValue.from("phase2-test-pepper-with-256-bits-minimum-material")
        val hasher = CredentialHasher(pepper)
        pepper.close()
        if (migrate) FlywayMigrator(dataSource, schemas = arrayOf(schema)).migrateAndValidate()
        return TestBundle(schema, dataSource, hasher, SharedMapService(dataSource, hasher, generator, clock))
    }

    private data class TestBundle(
        val schema: String,
        val dataSource: HikariDataSource,
        val hasher: CredentialHasher,
        val service: SharedMapService,
    ) : AutoCloseable {
        fun count(table: String): Long = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { result -> result.next(); result.getLong(1) }
            }
        }

        fun countWhere(table: String, where: String): Long = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table WHERE $where").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

        fun scalar(sql: String): String = requireNotNull(scalarOrNull(sql))

        fun scalarOrNull(sql: String): String? = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> result.next(); result.getString(1) }
            }
        }

        fun businessTables(): Set<String> = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name <> 'flyway_schema_history'",
            ).use { statement ->
                statement.setString(1, schema)
                statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
            }
        }

        fun databaseTextDump(): String = dataSource.connection.use { connection ->
            listOf("invites", "access_tokens", "idempotency_records", "audit_events").joinToString("\n") { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT row_to_json(t)::text FROM $table t").use { result ->
                        buildList { while (result.next()) add(result.getString(1)) }.joinToString("\n")
                    }
                }
            }
        }

        override fun close() {
            try {
                dataSource.close()
            } finally {
                hasher.close()
            }
        }
    }

    private class QueueCredentialGenerator(vararg values: String) : CredentialGenerator {
        private val queue = ConcurrentLinkedQueue(values.toList())
        override fun generate(kind: CredentialKind): String = queue.poll()
            ?: error("No deterministic credential remains for $kind")
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun invite(character: Char) = "esm_inv_" + character.toString().repeat(43)
    private fun device(character: Char) = "esm_dev_" + character.toString().repeat(43)

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.idempotency() {
        header("Idempotency-Key", UUID.randomUUID().toString())
    }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("eve_shared_map_phase2")
            .withUsername("eve_shared_map_phase2")
            .withPassword("phase2-test-password")
    }
}
