package dev.evesharedmap.server

import com.zaxxer.hikari.HikariDataSource
import dev.evesharedmap.server.api.CreateSharedMarkerRequest
import dev.evesharedmap.server.api.PROTOCOL_JSON
import dev.evesharedmap.server.api.UpdateSharedMarkerRequest
import dev.evesharedmap.server.config.DatabaseConfig
import dev.evesharedmap.server.config.SecretValue
import dev.evesharedmap.server.database.DatabaseFactory
import dev.evesharedmap.server.database.FlywayMigrator
import dev.evesharedmap.server.domain.AuthenticationPrincipal
import dev.evesharedmap.server.domain.WorkspaceCapability
import dev.evesharedmap.server.domain.WorkspaceRole
import dev.evesharedmap.server.health.ReadinessProbe
import dev.evesharedmap.server.http.configureHttp
import dev.evesharedmap.server.marker.SharedMarkerService
import dev.evesharedmap.server.marker.SharedMarkerValidation
import dev.evesharedmap.server.security.CredentialHasher
import dev.evesharedmap.server.security.InMemoryTokenBucketRateLimiter
import dev.evesharedmap.server.security.SecureCredentialGenerator
import dev.evesharedmap.server.service.MutationResponse
import dev.evesharedmap.server.service.ServiceException
import dev.evesharedmap.server.service.SharedMapService
import dev.evesharedmap.server.universe.SolarSystemAllowlist
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class Phase3PostgreSqlIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `V1 to V2 to V3 upgrade preserves Phase 2 authentication and repeats cleanly`() {
        newBundle(migrate = false).use { bundle ->
            val phase2Directory = tempDirectory.resolve("phase2-migrations")
            Files.createDirectories(phase2Directory)
            listOf("V1__skeleton.sql", "V2__workspace_authentication.sql").forEach { name ->
                Files.copy(Path.of("src/main/resources/db/migration/$name"), phase2Directory.resolve(name))
            }
            val phase2 = Flyway.configure()
                .dataSource(bundle.dataSource)
                .schemas(bundle.schema)
                .defaultSchema(bundle.schema)
                .locations("filesystem:${phase2Directory.toAbsolutePath()}")
                .load()
            assertEquals(2, phase2.migrate().migrationsExecuted)
            phase2.validate()

            val bootstrap = bundle.service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1))
            assertEquals(1L, bundle.count("workspaces"))
            assertEquals(1L, bundle.count("workspace_members"))

            val upgrade = FlywayMigrator(bundle.dataSource, schemas = arrayOf(bundle.schema)).migrateAndValidate()
            val repeat = FlywayMigrator(bundle.dataSource, schemas = arrayOf(bundle.schema)).migrateAndValidate()

            assertEquals(1, upgrade.migrationsExecuted)
            assertEquals("3", upgrade.currentVersion)
            assertEquals(0, repeat.migrationsExecuted)
            assertEquals("3", repeat.currentVersion)
            assertTrue("shared_markers" in bundle.businessTables())
            assertEquals(1L, bundle.count("workspaces"))
            assertEquals(
                bootstrap.memberId,
                bundle.service.exchangeInvite(bootstrap.rawInviteSecret, "Admin Device", "upgrade-exchange")
                    .principal.membership.memberId,
            )
        }
    }

    @Test
    fun `marker schema enforces frozen constraints and service returns deterministic snapshots`() {
        newBundle().use { bundle ->
            val admin = bundle.bootstrapAdmin()
            val workspaceId = admin.membership.workspaceId
            val userId = admin.membership.user.userId

            bundle.createMarker(admin, 30004759, "Null", "BLUE", listOf("strategic"), null)
            bundle.createMarker(admin, 30000142, "High", "WHITE", listOf("staging"), null)
            bundle.createMarker(admin, 30002537, "Low", "RED", emptyList(), "lowsec")

            val snapshot = bundle.markerService.listSnapshot(workspaceId)
            assertEquals(listOf(30000142, 30002537, 30004759), snapshot.markers.map { it.systemId })
            assertEquals(3, snapshot.revision)

            bundle.execute("UPDATE users SET display_name = 'Renamed Admin' WHERE user_id = '$userId'")
            assertEquals("Renamed Admin", bundle.markerService.listSnapshot(workspaceId).markers.first().createdBy.displayName)

            val invalidStatements = listOf(
                rawMarkerInsert(workspaceId, userId, systemId = 0),
                rawMarkerInsert(workspaceId, userId, systemId = 30009999, color = "BLACK"),
                rawMarkerInsert(workspaceId, userId, systemId = 30009998, tags = "ARRAY['danger','danger']"),
                rawMarkerInsert(workspaceId, userId, systemId = 30009997, version = 0),
                rawMarkerInsert(workspaceId, userId, systemId = 30009996, updatedBeforeCreated = true),
            )
            invalidStatements.forEach { sql ->
                assertFailsWith<SQLException> { bundle.execute(sql) }
            }

            assertFailsWith<SQLException> {
                bundle.execute(rawMarkerInsert(workspaceId, userId, systemId = 30000142))
            }
            assertEquals(3L, bundle.count("shared_markers"))
            assertTrue(bundle.indexes("shared_markers").contains("shared_markers_workspace_updated_idx"))
            assertTrue(bundle.indexes("shared_markers").contains("shared_markers_workspace_system_unique"))
        }
    }

    @Test
    fun `marker idempotency expires after 24 hours and failed audit rolls back the whole mutation`() {
        newBundle().use { bundle ->
            val admin = bundle.bootstrapAdmin()
            val key = UUID.randomUUID()
            bundle.createMarker(
                admin,
                30000142,
                "First",
                "BLUE",
                emptyList(),
                null,
                key = key,
                fingerprint = ByteArray(32) { 1 },
            )
            bundle.execute(
                "UPDATE idempotency_records SET created_at = now() - interval '25 hours', " +
                    "expires_at = now() - interval '1 hour' " +
                    "WHERE token_id = '${admin.tokenId}' AND idempotency_key = '$key'",
            )
            bundle.createMarker(
                admin,
                30002537,
                "Reused after expiry",
                "RED",
                emptyList(),
                null,
                key = key,
                fingerprint = ByteArray(32) { 2 },
            )
            assertEquals(2L, bundle.count("shared_markers"))
            assertEquals(2, bundle.markerService.listSnapshot(admin.membership.workspaceId).revision)

            bundle.execute(
                "ALTER TABLE audit_events ADD CONSTRAINT phase3_reject_marker_audit " +
                    "CHECK (action <> 'MARKER_CREATED') NOT VALID",
            )
            assertFailsWith<SQLException> {
                bundle.createMarker(admin, 30004759, "Must roll back", "WHITE", emptyList(), null, byte = 3)
            }
            assertEquals(2L, bundle.count("shared_markers"))
            assertEquals(2, bundle.markerService.listSnapshot(admin.membership.workspaceId).revision)
            assertEquals(2L, bundle.countWhere("audit_events", "action = 'MARKER_CREATED'"))
            assertEquals(1L, bundle.count("idempotency_records"))
        }
    }

    @Test
    fun `workspace marker limit is enforced before a five hundred first marker`() {
        newBundle().use { bundle ->
            val admin = bundle.bootstrapAdmin()
            val workspaceId = admin.membership.workspaceId
            val userId = admin.membership.user.userId
            bundle.execute(
                """
                INSERT INTO shared_markers (
                    workspace_id, system_id, name, color, tags, notes,
                    created_by_user_id, updated_by_user_id, created_at, updated_at, version
                )
                SELECT '$workspaceId', generated_id, 'Seed ' || generated_id, 'WHITE', ARRAY[]::text[], NULL,
                       '$userId', '$userId', now(), now(), 1
                FROM generate_series(1, 500) AS generated_id
                """.trimIndent(),
            )

            val error = assertFailsWith<ServiceException> {
                bundle.createMarker(admin, 30000142, "Overflow", "BLUE", emptyList(), null)
            }
            assertEquals("INVALID_ARGUMENT", error.code)
            assertEquals(500L, bundle.count("shared_markers"))
            assertEquals(0L, bundle.count("idempotency_records"))
        }
    }

    @Test
    fun `HTTP marker lifecycle enforces roles locking idempotency audit and hard delete`() = testApplication {
        val bundle = newBundle()
        try {
            val admin = bundle.bootstrapAdminIssued()
            val editor = bundle.createMemberDevice(admin.principal, WorkspaceRole.EDITOR, "Editor")
            val viewer = bundle.createMemberDevice(admin.principal, WorkspaceRole.VIEWER, "Viewer")
            val workspaceId = admin.principal.membership.workspaceId
            application {
                configureHttp(
                    ReadinessProbe { true },
                    "test",
                    sharedMapService = bundle.service,
                    sharedMarkerService = bundle.markerService,
                    universeBuild = bundle.allowlist.universeBuild,
                    rateLimiter = InMemoryTokenBucketRateLimiter(),
                )
            }

            val empty = client.get("/api/v1/workspaces/$workspaceId/markers") { bearer(viewer.rawSecret) }
            assertEquals(HttpStatusCode.OK, empty.status)
            assertEquals(0, empty.json()["revision"]!!.jsonPrimitive.long)
            assertTrue(empty.json()["markers"]!!.jsonArray.isEmpty())

            val viewerCreate = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(viewer.rawSecret)
                idempotency()
                jsonBody(createBody(30004759, "Blocked", "BLUE", emptyList(), null))
            }
            assertEquals(HttpStatusCode.Forbidden, viewerCreate.status)

            val createKey = UUID.randomUUID().toString()
            val requestBody = createBody(
                30004759,
                "  Northern staging  ",
                "BLUE",
                listOf("staging", "strategic"),
                "  Form before 19:30 UTC.  ",
            )
            val created = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(editor.rawSecret)
                header("Idempotency-Key", createKey)
                jsonBody(requestBody)
            }
            val createReplay = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(editor.rawSecret)
                header("Idempotency-Key", createKey)
                jsonBody(requestBody)
            }
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(created.bodyAsText(), createReplay.bodyAsText())
            assertEquals(created.headers[HttpHeaders.Location], createReplay.headers[HttpHeaders.Location])
            val markerId = created.json()["markerId"]!!.jsonPrimitive.content
            assertEquals(1, created.json()["version"]!!.jsonPrimitive.long)
            assertEquals("Northern staging", created.json()["name"]!!.jsonPrimitive.content)

            val reusedKey = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(editor.rawSecret)
                header("Idempotency-Key", createKey)
                jsonBody(createBody(30002537, "Different request", "RED", emptyList(), null))
            }
            assertEquals(HttpStatusCode.Conflict, reusedKey.status)
            assertContains(reusedKey.bodyAsText(), "IDEMPOTENCY_KEY_REUSED")

            val duplicate = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(admin.rawSecret)
                idempotency()
                jsonBody(requestBody)
            }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertContains(duplicate.bodyAsText(), "MARKER_ALREADY_EXISTS")

            val viewerSnapshot = client.get("/api/v1/workspaces/$workspaceId/markers") { bearer(viewer.rawSecret) }
            val adminSnapshot = client.get("/api/v1/workspaces/$workspaceId/markers") { bearer(admin.rawSecret) }
            assertEquals(1, viewerSnapshot.json()["revision"]!!.jsonPrimitive.long)
            assertEquals(
                viewerSnapshot.json()["markers"],
                adminSnapshot.json()["markers"],
            )

            val patchKey = UUID.randomUUID().toString()
            val patchBody = updateBody(1, "Moved staging", "ORANGE", listOf("danger"), null)
            val updated = client.patch("/api/v1/workspaces/$workspaceId/markers/$markerId") {
                bearer(editor.rawSecret)
                header("Idempotency-Key", patchKey)
                jsonBody(patchBody)
            }
            val updateReplay = client.patch("/api/v1/workspaces/$workspaceId/markers/$markerId") {
                bearer(editor.rawSecret)
                header("Idempotency-Key", patchKey)
                jsonBody(patchBody)
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals(updated.bodyAsText(), updateReplay.bodyAsText())
            assertEquals(2, updated.json()["version"]!!.jsonPrimitive.long)
            assertEquals(editor.principal.membership.user.userId.toString(), updated.json()["updatedBy"]!!.jsonObject["userId"]!!.jsonPrimitive.content)

            val stale = client.patch("/api/v1/workspaces/$workspaceId/markers/$markerId") {
                bearer(admin.rawSecret)
                idempotency()
                jsonBody(updateBody(1, "Stale", "RED", emptyList(), null))
            }
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertContains(stale.bodyAsText(), "MARKER_VERSION_CONFLICT")
            assertEquals(2, stale.json()["details"]!!.jsonObject["currentVersion"]!!.jsonPrimitive.long)
            assertEquals(markerId, stale.json()["details"]!!.jsonObject["currentMarker"]!!.jsonObject["markerId"]!!.jsonPrimitive.content)

            val viewerPatch = client.patch("/api/v1/workspaces/$workspaceId/markers/$markerId") {
                bearer(viewer.rawSecret)
                idempotency()
                jsonBody(updateBody(2, "No", "RED", emptyList(), null))
            }
            val viewerDelete = client.delete("/api/v1/workspaces/$workspaceId/markers/$markerId?expectedVersion=2") {
                bearer(viewer.rawSecret)
                idempotency()
            }
            assertEquals(HttpStatusCode.Forbidden, viewerPatch.status)
            assertEquals(HttpStatusCode.Forbidden, viewerDelete.status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/v1/workspaces/$workspaceId/members") { bearer(editor.rawSecret) }.status,
            )

            val staleDelete = client.delete("/api/v1/workspaces/$workspaceId/markers/$markerId?expectedVersion=1") {
                bearer(admin.rawSecret)
                idempotency()
            }
            assertEquals(HttpStatusCode.Conflict, staleDelete.status)
            assertContains(staleDelete.bodyAsText(), "MARKER_VERSION_CONFLICT")
            assertEquals(2, staleDelete.json()["details"]!!.jsonObject["currentVersion"]!!.jsonPrimitive.long)

            val deleteKey = UUID.randomUUID().toString()
            val deleted = client.delete("/api/v1/workspaces/$workspaceId/markers/$markerId?expectedVersion=2") {
                bearer(admin.rawSecret)
                header("Idempotency-Key", deleteKey)
            }
            val deleteReplay = client.delete("/api/v1/workspaces/$workspaceId/markers/$markerId?expectedVersion=2") {
                bearer(admin.rawSecret)
                header("Idempotency-Key", deleteKey)
            }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(HttpStatusCode.NoContent, deleteReplay.status)
            val missingDelete = client.delete("/api/v1/workspaces/$workspaceId/markers/$markerId?expectedVersion=2") {
                bearer(admin.rawSecret)
                idempotency()
            }
            assertEquals(HttpStatusCode.NotFound, missingDelete.status)

            val finalSnapshot = client.get("/api/v1/workspaces/$workspaceId/markers") { bearer(viewer.rawSecret) }
            assertEquals(3, finalSnapshot.json()["revision"]!!.jsonPrimitive.long)
            assertTrue(finalSnapshot.json()["markers"]!!.jsonArray.isEmpty())
            assertEquals(0L, bundle.count("shared_markers"))
            assertEquals(1L, bundle.countWhere("audit_events", "action = 'MARKER_CREATED'"))
            assertEquals(1L, bundle.countWhere("audit_events", "action = 'MARKER_UPDATED'"))
            assertEquals(1L, bundle.countWhere("audit_events", "action = 'MARKER_DELETED'"))
            val auditText = bundle.scalar(
                "SELECT string_agg(metadata::text, '') FROM audit_events WHERE target_type = 'MARKER'",
            )
            assertFalse(auditText.contains("Form before"))
            assertFalse(auditText.contains("Moved staging"))
        } finally {
            bundle.close()
        }
    }

    @Test
    fun `workspace isolation role changes and revocation take effect on next marker request`() = testApplication {
        val bundle = newBundle()
        try {
            val adminA = bundle.bootstrapAdminIssued()
            val editorA = bundle.createMemberDevice(adminA.principal, WorkspaceRole.EDITOR, "Editor A")
            val adminB = bundle.seedIndependentWorkspace("Workspace B", WorkspaceRole.ADMIN)
            val workspaceA = adminA.principal.membership.workspaceId
            val workspaceB = adminB.principal.membership.workspaceId
            application {
                configureHttp(
                    ReadinessProbe { true },
                    "test",
                    sharedMapService = bundle.service,
                    sharedMarkerService = bundle.markerService,
                    universeBuild = bundle.allowlist.universeBuild,
                )
            }

            val markerA = client.post("/api/v1/workspaces/$workspaceA/markers") {
                bearer(editorA.rawSecret); idempotency(); jsonBody(createBody(30000142, "A", "BLUE", emptyList(), null))
            }
            val markerB = client.post("/api/v1/workspaces/$workspaceB/markers") {
                bearer(adminB.rawSecret); idempotency(); jsonBody(createBody(30002537, "B", "RED", emptyList(), null))
            }
            assertEquals(HttpStatusCode.Created, markerA.status)
            assertEquals(HttpStatusCode.Created, markerB.status)
            val markerBId = markerB.json()["markerId"]!!.jsonPrimitive.content

            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/workspaces/$workspaceB/markers") { bearer(editorA.rawSecret) }.status,
            )
            val foreignPatch = client.patch("/api/v1/workspaces/$workspaceB/markers/$markerBId") {
                bearer(editorA.rawSecret); idempotency(); jsonBody(updateBody(1, "Leaked", "WHITE", emptyList(), null))
            }
            assertEquals(HttpStatusCode.NotFound, foreignPatch.status)
            assertFalse(foreignPatch.bodyAsText().contains(markerBId))

            bundle.execute(
                "UPDATE workspace_members SET role = 'VIEWER', version = version + 1 WHERE member_id = '${editorA.principal.membership.memberId}'",
            )
            val viewerRead = client.get("/api/v1/workspaces/$workspaceA/markers") {
                bearer(editorA.rawSecret)
            }
            assertEquals(HttpStatusCode.OK, viewerRead.status)
            val downgraded = client.post("/api/v1/workspaces/$workspaceA/markers") {
                bearer(editorA.rawSecret); idempotency(); jsonBody(createBody(30002537, "No write", "WHITE", emptyList(), null))
            }
            assertEquals(HttpStatusCode.Forbidden, downgraded.status)

            val removeMembership = client.delete(
                "/api/v1/workspaces/$workspaceA/members/${editorA.principal.membership.memberId}?expectedVersion=2",
            ) {
                bearer(adminA.rawSecret)
                idempotency()
            }
            assertEquals(HttpStatusCode.NoContent, removeMembership.status)
            assertEquals(
                1,
                bundle.countWhere(
                    "access_tokens",
                    "token_id = '${editorA.principal.tokenId}'::uuid AND revoked_at IS NOT NULL",
                ),
            )
            val revoked = client.get("/api/v1/workspaces/$workspaceA/markers") { bearer(editorA.rawSecret) }
            assertEquals(HttpStatusCode.Forbidden, revoked.status)
            assertContains(revoked.bodyAsText(), "\"code\":\"FORBIDDEN\"")
            assertFalse(revoked.bodyAsText().contains("TOKEN_REVOKED"))
            assertEquals(1, bundle.markerService.listSnapshot(workspaceA).markers.size)
            assertEquals(1, bundle.markerService.listSnapshot(workspaceB).markers.size)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun `PostgreSQL races produce one marker one update and one update-delete winner`() {
        newBundle().use { bundle ->
            val editor = bundle.bootstrapAdmin()
            val sameSystem = runBlocking {
                listOf("A", "B").mapIndexed { index, name ->
                    async(Dispatchers.IO) {
                        runCatching {
                            bundle.createMarker(editor, 30000142, name, "BLUE", emptyList(), null, byte = index + 1)
                        }
                    }
                }.awaitAll()
            }
            assertEquals(1, sameSystem.count { it.isSuccess })
            assertEquals(1, sameSystem.count { (it.exceptionOrNull() as? ServiceException)?.code == "MARKER_ALREADY_EXISTS" })
            assertEquals(1L, bundle.count("shared_markers"))

            val sameKey = UUID.randomUUID()
            val sameFingerprint = ByteArray(32) { 9 }
            val sameKeyResults = runBlocking {
                List(2) {
                    async(Dispatchers.IO) {
                        bundle.createMarker(
                            editor,
                            30002537,
                            "Same key",
                            "RED",
                            emptyList(),
                            null,
                            key = sameKey,
                            fingerprint = sameFingerprint,
                        )
                    }
                }.awaitAll()
            }
            assertEquals(sameKeyResults[0].response.responseBody, sameKeyResults[1].response.responseBody)
            assertEquals(1, sameKeyResults.count { it.replayed })
            assertEquals(2L, bundle.count("shared_markers"))

            val updateTarget = bundle.createMarker(editor, 30004759, "Race", "WHITE", emptyList(), null)
                .response.responseBody!!.jsonObject["markerId"]!!.jsonPrimitive.content.let(UUID::fromString)
            val updates = runBlocking {
                listOf("Update A", "Update B").mapIndexed { index, name ->
                    async(Dispatchers.IO) {
                        runCatching {
                            bundle.updateMarker(editor, updateTarget, 1, name, byte = 20 + index)
                        }
                    }
                }.awaitAll()
            }
            assertEquals(1, updates.count { it.isSuccess })
            assertEquals(1, updates.count { (it.exceptionOrNull() as? ServiceException)?.code == "MARKER_VERSION_CONFLICT" })
            assertEquals(2, bundle.markerService.listSnapshot(editor.membership.workspaceId).markers.single { it.markerId == updateTarget }.version)

            val sameKeyUpdateTarget = bundle.createMarker(editor, 30000002, "Same-key update", "WHITE", emptyList(), null)
                .response.responseBody!!.jsonObject["markerId"]!!.jsonPrimitive.content.let(UUID::fromString)
            val updateKey = UUID.randomUUID()
            val updateFingerprint = ByteArray(32) { 61 }
            val sameKeyUpdates = runBlocking {
                List(2) {
                    async(Dispatchers.IO) {
                        bundle.updateMarker(
                            editor,
                            sameKeyUpdateTarget,
                            1,
                            "Updated once",
                            byte = 61,
                            key = updateKey,
                            fingerprint = updateFingerprint,
                        )
                    }
                }.awaitAll()
            }
            assertEquals(1, sameKeyUpdates.count { it.replayed })
            assertEquals(
                2,
                bundle.markerService.listSnapshot(editor.membership.workspaceId).markers
                    .single { it.markerId == sameKeyUpdateTarget }.version,
            )

            val sameKeyDeleteTarget = bundle.createMarker(editor, 30000003, "Same-key delete", "WHITE", emptyList(), null)
                .response.responseBody!!.jsonObject["markerId"]!!.jsonPrimitive.content.let(UUID::fromString)
            val deleteKey = UUID.randomUUID()
            val deleteFingerprint = ByteArray(32) { 62 }
            val sameKeyDeletes = runBlocking {
                List(2) {
                    async(Dispatchers.IO) {
                        bundle.deleteMarker(
                            editor,
                            sameKeyDeleteTarget,
                            1,
                            byte = 62,
                            key = deleteKey,
                            fingerprint = deleteFingerprint,
                        )
                    }
                }.awaitAll()
            }
            assertEquals(1, sameKeyDeletes.count { it.replayed })
            assertFalse(
                bundle.markerService.listSnapshot(editor.membership.workspaceId).markers
                    .any { it.markerId == sameKeyDeleteTarget },
            )

            val deleteTarget = bundle.createMarker(editor, 30000001, "Update delete", "GREEN", emptyList(), null)
                .response.responseBody!!.jsonObject["markerId"]!!.jsonPrimitive.content.let(UUID::fromString)
            val mutationRace = runBlocking {
                listOf(
                    async(Dispatchers.IO) { runCatching { bundle.updateMarker(editor, deleteTarget, 1, "Updated", byte = 40) } },
                    async(Dispatchers.IO) { runCatching { bundle.deleteMarker(editor, deleteTarget, 1, byte = 41) } },
                ).awaitAll()
            }
            assertEquals(1, mutationRace.count { it.isSuccess })
            assertEquals(1, mutationRace.count { it.isFailure })
            val loserCode = (mutationRace.single { it.isFailure }.exceptionOrNull() as ServiceException).code
            assertTrue(loserCode == "MARKER_VERSION_CONFLICT" || loserCode == "NOT_FOUND")
            val final = bundle.markerService.listSnapshot(editor.membership.workspaceId).markers.find { it.markerId == deleteTarget }
            assertTrue(final == null || final.version == 2L)
        }
    }

    @Test
    fun `marker request validation text security and rate limiting remain idempotency-safe`() = testApplication {
        val bundle = newBundle()
        try {
            val admin = bundle.bootstrapAdminIssued()
            val rateEditor = bundle.createMemberDevice(admin.principal, WorkspaceRole.EDITOR, "Rate Editor")
            val workspaceId = admin.principal.membership.workspaceId
            application {
                configureHttp(
                    ReadinessProbe { true },
                    "test",
                    sharedMapService = bundle.service,
                    sharedMarkerService = bundle.markerService,
                    universeBuild = bundle.allowlist.universeBuild,
                    rateLimiter = InMemoryTokenBucketRateLimiter(),
                )
            }

            val missingKey = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(admin.rawSecret); jsonBody(createBody(30000142, "Name", "BLUE", emptyList(), null))
            }
            val invalidKey = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(admin.rawSecret); header("Idempotency-Key", "NOT-A-UUID")
                jsonBody(createBody(30000142, "Name", "BLUE", emptyList(), null))
            }
            assertEquals(HttpStatusCode.BadRequest, missingKey.status)
            assertEquals(HttpStatusCode.BadRequest, invalidKey.status)

            val invalidBodies = listOf(
                createBody(0, "Name", "BLUE", emptyList(), null),
                createBody(Int.MAX_VALUE, "Name", "BLUE", emptyList(), null),
                createBody(30000142, " ", "BLUE", emptyList(), null),
                createBody(30000142, "Name", "#fff", emptyList(), null),
                createBody(30000142, "Name", "BLUE", listOf("danger", "danger"), null),
                createBody(30000142, "Name", "BLUE", listOf("Bad Tag"), null),
                createBody(30000142, "Name", "BLUE", emptyList(), "x".repeat(2_001)),
                createBody(30000142, "Name", "BLUE", emptyList(), "bad\u0000notes"),
            )
            invalidBodies.forEach { body ->
                val response = client.post("/api/v1/workspaces/$workspaceId/markers") {
                    bearer(admin.rawSecret); idempotency(); jsonBody(body)
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
                assertContains(response.bodyAsText(), "INVALID_ARGUMENT")
            }

            val unknownField = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(admin.rawSecret); idempotency(); jsonBody(
                    """{"systemId":30000142,"name":"Name","color":"BLUE","tags":[],"notes":null,"workspaceId":"${UUID.randomUUID()}"}""",
                )
            }
            val invalidJson = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(admin.rawSecret); idempotency(); jsonBody("{")
            }
            val oversized = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(admin.rawSecret); idempotency(); contentType(ContentType.Application.Json)
                setBody("x".repeat(33 * 1024))
            }
            assertEquals(HttpStatusCode.BadRequest, unknownField.status)
            assertEquals(HttpStatusCode.BadRequest, invalidJson.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/workspaces/not-a-uuid/markers") { bearer(admin.rawSecret) }.status,
            )

            val strategicText = "Robert'); DROP TABLE shared_markers;--"
            val strategicNotes = "{\"plan\":\"<script>alert(1)</script>\"}"
            val createKey = UUID.randomUUID().toString()
            val secureBody = createBody(30000142, strategicText, "PURPLE", listOf("custom.tag"), strategicNotes)
            val secureCreated = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(admin.rawSecret); header("Idempotency-Key", createKey); jsonBody(secureBody)
            }
            assertEquals(HttpStatusCode.Created, secureCreated.status)
            assertEquals(strategicText, secureCreated.json()["name"]!!.jsonPrimitive.content)
            assertEquals(strategicNotes, secureCreated.json()["notes"]!!.jsonPrimitive.content)
            assertEquals(1L, bundle.count("shared_markers"))
            assertFalse(bundle.scalar("SELECT metadata::text FROM audit_events WHERE action = 'MARKER_CREATED'").contains("script"))

            val markerId = secureCreated.json()["markerId"]!!.jsonPrimitive.content
            val forbiddenSystemChange = client.patch("/api/v1/workspaces/$workspaceId/markers/$markerId") {
                bearer(admin.rawSecret); idempotency(); jsonBody(
                    """{"expectedVersion":1,"systemId":30002537,"name":"Moved","color":"BLUE","tags":[],"notes":null}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, forbiddenSystemChange.status)

            val rateKey = UUID.randomUUID().toString()
            val rateBody = createBody(30000001, "Rate first", "WHITE", emptyList(), null)
            val rateFirst = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(rateEditor.rawSecret); header("Idempotency-Key", rateKey); jsonBody(rateBody)
            }
            assertEquals(HttpStatusCode.Created, rateFirst.status)
            (30000002..30000030).forEach { systemId ->
                val response = client.post("/api/v1/workspaces/$workspaceId/markers") {
                    bearer(rateEditor.rawSecret); idempotency(); jsonBody(createBody(systemId, "M$systemId", "WHITE", emptyList(), null))
                }
                assertEquals(HttpStatusCode.Created, response.status, "systemId=$systemId ${response.bodyAsText()}")
            }
            val replayAfterLimit = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(rateEditor.rawSecret); header("Idempotency-Key", rateKey); jsonBody(rateBody)
            }
            assertEquals(HttpStatusCode.Created, replayAfterLimit.status)
            val rateLimited = client.post("/api/v1/workspaces/$workspaceId/markers") {
                bearer(rateEditor.rawSecret); idempotency(); jsonBody(createBody(30000031, "Rate limited", "WHITE", emptyList(), null))
            }
            assertEquals(HttpStatusCode.TooManyRequests, rateLimited.status)
            assertNotNull(rateLimited.headers[HttpHeaders.RetryAfter])
        } finally {
            bundle.close()
        }
    }

    private fun newBundle(migrate: Boolean = true): TestBundle {
        val schema = "phase3_${UUID.randomUUID().toString().replace("-", "")}" 
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
        val pepper = SecretValue.from("phase3-test-pepper-with-256-bits-minimum-material")
        val hasher = CredentialHasher(pepper)
        pepper.close()
        if (migrate) FlywayMigrator(dataSource, schemas = arrayOf(schema)).migrateAndValidate()
        val allowlist = SolarSystemAllowlist.load()
        return TestBundle(
            schema,
            dataSource,
            hasher,
            allowlist,
            SharedMapService(dataSource, hasher, SecureCredentialGenerator()),
            SharedMarkerService(dataSource, SharedMarkerValidation(allowlist)),
        )
    }

    private data class TestBundle(
        val schema: String,
        val dataSource: HikariDataSource,
        val hasher: CredentialHasher,
        val allowlist: SolarSystemAllowlist,
        val service: SharedMapService,
        val markerService: SharedMarkerService,
    ) : AutoCloseable {
        fun bootstrapAdminIssued() = service.bootstrapAdmin("Admin", "Workspace", Duration.ofHours(1)).let { bootstrap ->
            service.exchangeInvite(bootstrap.rawInviteSecret, "Admin Device", "bootstrap-exchange")
        }

        fun bootstrapAdmin(): AuthenticationPrincipal = bootstrapAdminIssued().principal

        fun createMemberDevice(
            actor: AuthenticationPrincipal,
            role: WorkspaceRole,
            displayName: String,
        ): dev.evesharedmap.server.domain.IssuedDeviceToken {
            val memberResult = service.executeIdempotent(
                actor.tokenId,
                UUID.randomUUID(),
                UUID.randomUUID().toString().toByteArray().copyOf(32),
                WorkspaceCapability.ADMIN,
            ) { connection ->
                val member = service.createMember(connection, actor, displayName, role, "setup-member")
                MutationResponse(201, buildJsonObject { put("memberId", member.memberId.toString()) })
            }
            val memberId = memberResult.response.responseBody!!.jsonObject["memberId"]!!.jsonPrimitive.content
                .let(UUID::fromString)
            var rawInvite: String? = null
            service.executeIdempotent(
                actor.tokenId,
                UUID.randomUUID(),
                UUID.randomUUID().toString().toByteArray().copyOf(32),
                WorkspaceCapability.ADMIN,
                nonReplayableSecretResponse = true,
            ) { connection ->
                val invite = service.createInvite(connection, actor, memberId, Duration.ofHours(1), "setup-invite")
                rawInvite = invite.rawSecret
                MutationResponse(
                    201,
                    buildJsonObject { put("inviteToken", invite.rawSecret) },
                    storageBody = buildJsonObject { put("inviteId", invite.metadata.inviteId.toString()) },
                )
            }
            return service.exchangeInvite(requireNotNull(rawInvite), "$displayName Device", "setup-exchange")
        }

        fun seedIndependentWorkspace(name: String, role: WorkspaceRole): dev.evesharedmap.server.domain.IssuedDeviceToken {
            val rawToken = SecureCredentialGenerator().generate(dev.evesharedmap.server.security.CredentialKind.DEVICE)
            val tokenHash = hasher.hash(rawToken)
            val ids = dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val userId = connection.prepareStatement(
                        "INSERT INTO users (display_name, created_at, updated_at) VALUES ('Admin B', now(), now()) RETURNING user_id",
                    ).use { statement -> statement.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) } }
                    val workspaceId = connection.prepareStatement(
                        "INSERT INTO workspaces (name, created_at, updated_at) VALUES (?, now(), now()) RETURNING workspace_id",
                    ).use { statement ->
                        statement.setString(1, name)
                        statement.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
                    }
                    val memberId = connection.prepareStatement(
                        "INSERT INTO workspace_members (workspace_id, user_id, role, created_at, updated_at) VALUES (?, ?, ?, now(), now()) RETURNING member_id",
                    ).use { statement ->
                        statement.setObject(1, workspaceId)
                        statement.setObject(2, userId)
                        statement.setString(3, role.name)
                        statement.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
                    }
                    connection.prepareStatement(
                        "INSERT INTO access_tokens (member_id, token_hash, token_prefix, device_name, created_at, expires_at) VALUES (?, ?, ?, 'B Device', now(), now() + interval '90 days')",
                    ).use { statement ->
                        statement.setObject(1, memberId)
                        statement.setBytes(2, tokenHash)
                        statement.setString(3, CredentialHasher.operatorPrefix(rawToken))
                        statement.executeUpdate()
                    }
                    connection.commit()
                    workspaceId
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
            tokenHash.fill(0)
            val principal = service.authenticate(rawToken)
            assertEquals(ids, principal.membership.workspaceId)
            return dev.evesharedmap.server.domain.IssuedDeviceToken(principal, rawToken)
        }

        fun createMarker(
            actor: AuthenticationPrincipal,
            systemId: Int,
            name: String,
            color: String,
            tags: List<String>,
            notes: String?,
            byte: Int = 1,
            key: UUID = UUID.randomUUID(),
            fingerprint: ByteArray = ByteArray(32) { byte.toByte() },
        ) = service.executeIdempotent(
            actor.tokenId,
            key,
            fingerprint,
            WorkspaceCapability.MARKER_WRITE,
        ) { connection ->
            val marker = markerService.create(
                connection, actor, systemId, name, color, tags, notes, "direct-create",
            )
            MutationResponse(
                201,
                buildJsonObject {
                    put("markerId", marker.markerId.toString())
                    put("version", marker.version)
                },
            )
        }

        fun updateMarker(
            actor: AuthenticationPrincipal,
            markerId: UUID,
            expectedVersion: Long,
            name: String,
            byte: Int,
            key: UUID = UUID.randomUUID(),
            fingerprint: ByteArray = ByteArray(32) { byte.toByte() },
        ) = service.executeIdempotent(
            actor.tokenId,
            key,
            fingerprint,
            WorkspaceCapability.MARKER_WRITE,
        ) { connection ->
            val marker = markerService.update(
                connection, actor, markerId, expectedVersion, name, "ORANGE", emptyList(), null, "direct-update",
            )
            MutationResponse(200, buildJsonObject { put("version", marker.version) })
        }

        fun deleteMarker(
            actor: AuthenticationPrincipal,
            markerId: UUID,
            expectedVersion: Long,
            byte: Int,
            key: UUID = UUID.randomUUID(),
            fingerprint: ByteArray = ByteArray(32) { byte.toByte() },
        ) = service.executeIdempotent(
            actor.tokenId,
            key,
            fingerprint,
            WorkspaceCapability.MARKER_WRITE,
        ) { connection ->
            markerService.delete(connection, actor, markerId, expectedVersion, "direct-delete")
            MutationResponse(204, null)
        }

        fun count(table: String): Long = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { result -> result.next(); result.getLong(1) }
            }
        }

        fun countWhere(table: String, where: String): Long = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table WHERE $where").use { result -> result.next(); result.getLong(1) }
            }
        }

        fun scalar(sql: String): String = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> result.next(); result.getString(1) }
            }
        }

        fun execute(sql: String) {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
        }

        fun businessTables(): Set<String> = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name <> 'flyway_schema_history'",
            ).use { statement ->
                statement.setString(1, schema)
                statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
            }
        }

        fun indexes(table: String): Set<String> = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, table)
                statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
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

    private fun createBody(
        systemId: Int,
        name: String,
        color: String,
        tags: List<String>,
        notes: String?,
    ): String = PROTOCOL_JSON.encodeToString(CreateSharedMarkerRequest(systemId, name, color, tags, notes))

    private fun updateBody(
        expectedVersion: Long,
        name: String,
        color: String,
        tags: List<String>,
        notes: String?,
    ): String = PROTOCOL_JSON.encodeToString(UpdateSharedMarkerRequest(expectedVersion, name, color, tags, notes))

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.idempotency() {
        header("Idempotency-Key", UUID.randomUUID().toString())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.json(): JsonObject =
        PROTOCOL_JSON.parseToJsonElement(bodyAsText()).jsonObject

    private fun rawMarkerInsert(
        workspaceId: UUID,
        userId: UUID,
        systemId: Int,
        color: String = "BLUE",
        tags: String = "ARRAY[]::text[]",
        version: Long = 1,
        updatedBeforeCreated: Boolean = false,
    ): String {
        val updatedAt = if (updatedBeforeCreated) "now() - interval '1 hour'" else "now()"
        return """
            INSERT INTO shared_markers (
                workspace_id, system_id, name, color, tags, notes,
                created_by_user_id, updated_by_user_id, created_at, updated_at, version
            ) VALUES (
                '$workspaceId', $systemId, 'Raw', '$color', $tags, NULL,
                '$userId', '$userId', now(), $updatedAt, $version
            )
        """.trimIndent()
    }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("eve_shared_map_phase3")
            .withUsername("eve_shared_map_phase3")
            .withPassword("phase3-test-password")
    }
}
