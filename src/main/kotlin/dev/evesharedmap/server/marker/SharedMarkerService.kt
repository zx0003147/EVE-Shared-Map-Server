package dev.evesharedmap.server.marker

import dev.evesharedmap.server.domain.AuthenticationPrincipal
import dev.evesharedmap.server.service.ServiceErrors
import dev.evesharedmap.server.service.ServiceException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class SharedMarkerService(
    private val dataSource: DataSource,
    private val validation: SharedMarkerValidation,
    private val clock: Clock = Clock.systemUTC(),
) {
    val universeBuild: String get() = validation.universeBuild

    fun listSnapshot(workspaceId: UUID): SharedMarkerSnapshot = dataSource.connection.use { connection ->
        connection.prepareStatement(SNAPSHOT_QUERY).use { statement ->
            statement.setObject(1, workspaceId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ServiceErrors.notFound()
                val revision = result.getLong("workspace_revision")
                val markers = buildList {
                    do {
                        if (result.getObject("marker_id") != null) add(result.toMarker())
                    } while (result.next())
                }
                SharedMarkerSnapshot(workspaceId, revision, clock.instant(), markers)
            }
        }
    }

    fun create(
        connection: Connection,
        actor: AuthenticationPrincipal,
        systemId: Int,
        name: String,
        color: String,
        tags: List<String>,
        notes: String?,
        requestId: String,
    ): SharedMarker {
        val validatedSystemId = validation.systemId(systemId)
        val fields = validation.fields(name, color, tags, notes)
        val workspaceId = actor.membership.workspaceId
        connection.lockWorkspace(workspaceId)
        if (connection.countMarkers(workspaceId) >= MAX_MARKERS_PER_WORKSPACE) {
            throw ServiceErrors.invalid("workspaceId", "MARKER_LIMIT_REACHED")
        }

        val now = clock.instant()
        val markerId = try {
            connection.prepareStatement(
                """
                INSERT INTO shared_markers (
                    workspace_id, system_id, name, color, tags, notes,
                    created_by_user_id, updated_by_user_id, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                RETURNING marker_id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, workspaceId)
                statement.setInt(2, validatedSystemId)
                statement.setString(3, fields.name)
                statement.setString(4, fields.color.name)
                statement.setTextArray(5, connection, fields.tags)
                statement.setNullableString(6, fields.notes)
                statement.setObject(7, actor.membership.user.userId)
                statement.setObject(8, actor.membership.user.userId)
                statement.setInstant(9, now)
                statement.setInstant(10, now)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getObject(1, UUID::class.java)
                }
            }
        } catch (error: SQLException) {
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) throw markerAlreadyExists()
            throw error
        }

        connection.incrementWorkspaceRevision(workspaceId, now)
        connection.insertMarkerAudit(
            actor = actor,
            action = "MARKER_CREATED",
            markerId = markerId,
            systemId = validatedSystemId,
            timestamp = now,
            metadata = buildJsonObject {
                put("requestId", requestId)
                put("version", 1)
            },
        )
        return connection.findMarker(workspaceId, markerId)
            ?: error("Newly created Shared Marker disappeared inside its transaction.")
    }

    fun update(
        connection: Connection,
        actor: AuthenticationPrincipal,
        markerId: UUID,
        expectedVersion: Long,
        name: String,
        color: String,
        tags: List<String>,
        notes: String?,
        requestId: String,
    ): SharedMarker {
        requireExpectedVersion(expectedVersion)
        val fields = validation.fields(name, color, tags, notes)
        val workspaceId = actor.membership.workspaceId
        val current = connection.findMarker(workspaceId, markerId) ?: throw ServiceErrors.notFound()
        if (current.version != expectedVersion) throw markerVersionConflict(expectedVersion, current)

        val now = clock.instant()
        val updated = connection.prepareStatement(
            """
            UPDATE shared_markers
            SET name = ?, color = ?, tags = ?, notes = ?, updated_by_user_id = ?,
                updated_at = ?, version = version + 1
            WHERE workspace_id = ? AND marker_id = ? AND version = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, fields.name)
            statement.setString(2, fields.color.name)
            statement.setTextArray(3, connection, fields.tags)
            statement.setNullableString(4, fields.notes)
            statement.setObject(5, actor.membership.user.userId)
            statement.setInstant(6, now)
            statement.setObject(7, workspaceId)
            statement.setObject(8, markerId)
            statement.setLong(9, expectedVersion)
            statement.executeUpdate() == 1
        }
        if (!updated) {
            val latest = connection.findMarker(workspaceId, markerId) ?: throw ServiceErrors.notFound()
            throw markerVersionConflict(expectedVersion, latest)
        }

        val next = connection.findMarker(workspaceId, markerId)
            ?: error("Updated Shared Marker disappeared inside its transaction.")
        connection.incrementWorkspaceRevision(workspaceId, now)
        connection.insertMarkerAudit(
            actor = actor,
            action = "MARKER_UPDATED",
            markerId = markerId,
            systemId = current.systemId,
            timestamp = now,
            metadata = buildJsonObject {
                put("requestId", requestId)
                put("oldVersion", current.version)
                put("newVersion", next.version)
                put("changedFields", JsonArray(changedFields(current, fields).map(::JsonPrimitive)))
            },
        )
        return next
    }

    fun delete(
        connection: Connection,
        actor: AuthenticationPrincipal,
        markerId: UUID,
        expectedVersion: Long,
        requestId: String,
    ) {
        requireExpectedVersion(expectedVersion)
        val workspaceId = actor.membership.workspaceId
        val deletedSystemId = connection.prepareStatement(
            """
            DELETE FROM shared_markers
            WHERE workspace_id = ? AND marker_id = ? AND version = ?
            RETURNING system_id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, workspaceId)
            statement.setObject(2, markerId)
            statement.setLong(3, expectedVersion)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        }
        if (deletedSystemId == null) {
            val current = connection.findMarker(workspaceId, markerId) ?: throw ServiceErrors.notFound()
            throw markerVersionConflict(expectedVersion, current)
        }

        val now = clock.instant()
        connection.incrementWorkspaceRevision(workspaceId, now)
        connection.insertMarkerAudit(
            actor = actor,
            action = "MARKER_DELETED",
            markerId = markerId,
            systemId = deletedSystemId,
            timestamp = now,
            metadata = buildJsonObject {
                put("requestId", requestId)
                put("version", expectedVersion)
            },
        )
    }

    private fun requireExpectedVersion(expectedVersion: Long) {
        if (expectedVersion <= 0) throw ServiceErrors.invalid("expectedVersion", "MUST_BE_POSITIVE")
    }

    private fun markerAlreadyExists(): ServiceException = ServiceException(
        409,
        "MARKER_ALREADY_EXISTS",
        "The Workspace already has a Shared Marker for this solar system.",
    )

    private fun markerVersionConflict(expectedVersion: Long, current: SharedMarker): ServiceException =
        ServiceException(
            409,
            "MARKER_VERSION_CONFLICT",
            "The Shared Marker was changed by another member.",
            buildJsonObject {
                put("expectedVersion", expectedVersion)
                put("currentVersion", current.version)
                put("currentMarker", current.toProtocolJson())
            },
        )

    private fun changedFields(current: SharedMarker, next: SharedMarkerFields): List<String> = buildList {
        if (current.name != next.name) add("name")
        if (current.color != next.color) add("color")
        if (current.tags != next.tags) add("tags")
        if (current.notes != next.notes) add("notes")
    }

    private companion object {
        const val MAX_MARKERS_PER_WORKSPACE = 500
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"

        val SNAPSHOT_QUERY = """
            SELECT w.revision AS workspace_revision,
                   m.marker_id, m.workspace_id, m.system_id, m.name, m.color, m.tags, m.notes,
                   m.created_by_user_id, created_user.display_name AS created_display_name,
                   m.updated_by_user_id, updated_user.display_name AS updated_display_name,
                   m.created_at, m.updated_at, m.version
            FROM workspaces w
            LEFT JOIN shared_markers m ON m.workspace_id = w.workspace_id
            LEFT JOIN users created_user ON created_user.user_id = m.created_by_user_id
            LEFT JOIN users updated_user ON updated_user.user_id = m.updated_by_user_id
            WHERE w.workspace_id = ?
            ORDER BY m.system_id NULLS LAST, m.marker_id NULLS LAST
        """.trimIndent()
    }
}

private fun Connection.lockWorkspace(workspaceId: UUID) {
    prepareStatement("SELECT workspace_id FROM workspaces WHERE workspace_id = ? FOR UPDATE").use { statement ->
        statement.setObject(1, workspaceId)
        statement.executeQuery().use { result -> if (!result.next()) throw ServiceErrors.notFound() }
    }
}

private fun Connection.countMarkers(workspaceId: UUID): Int =
    prepareStatement("SELECT count(*) FROM shared_markers WHERE workspace_id = ?").use { statement ->
        statement.setObject(1, workspaceId)
        statement.executeQuery().use { result -> result.next(); result.getInt(1) }
    }

private fun Connection.incrementWorkspaceRevision(workspaceId: UUID, now: Instant) {
    prepareStatement(
        "UPDATE workspaces SET revision = revision + 1, updated_at = ? WHERE workspace_id = ?",
    ).use { statement ->
        statement.setInstant(1, now)
        statement.setObject(2, workspaceId)
        check(statement.executeUpdate() == 1)
    }
}

private fun Connection.findMarker(workspaceId: UUID, markerId: UUID): SharedMarker? =
    prepareStatement(
        """
        SELECT m.marker_id, m.workspace_id, m.system_id, m.name, m.color, m.tags, m.notes,
               m.created_by_user_id, created_user.display_name AS created_display_name,
               m.updated_by_user_id, updated_user.display_name AS updated_display_name,
               m.created_at, m.updated_at, m.version
        FROM shared_markers m
        JOIN users created_user ON created_user.user_id = m.created_by_user_id
        JOIN users updated_user ON updated_user.user_id = m.updated_by_user_id
        WHERE m.workspace_id = ? AND m.marker_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workspaceId)
        statement.setObject(2, markerId)
        statement.executeQuery().use { result -> if (result.next()) result.toMarker() else null }
    }

private fun Connection.insertMarkerAudit(
    actor: AuthenticationPrincipal,
    action: String,
    markerId: UUID,
    systemId: Int,
    timestamp: Instant,
    metadata: JsonObject,
) {
    prepareStatement(
        """
        INSERT INTO audit_events (
            workspace_id, actor_user_id, actor_display_name, action, target_type,
            target_id, system_id, timestamp, metadata
        ) VALUES (?, ?, ?, ?, 'MARKER', ?, ?, ?, CAST(? AS jsonb))
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, actor.membership.workspaceId)
        statement.setObject(2, actor.membership.user.userId)
        statement.setString(3, actor.membership.user.displayName)
        statement.setString(4, action)
        statement.setObject(5, markerId)
        statement.setInt(6, systemId)
        statement.setInstant(7, timestamp)
        statement.setString(8, metadata.toString())
        check(statement.executeUpdate() == 1)
    }
}

private fun ResultSet.toMarker(): SharedMarker {
    val sqlTags = getArray("tags")
    val tags = try {
        (sqlTags.array as Array<*>).map { it as String }
    } finally {
        sqlTags.free()
    }
    return SharedMarker(
        markerId = getObject("marker_id", UUID::class.java),
        workspaceId = getObject("workspace_id", UUID::class.java),
        systemId = getInt("system_id"),
        name = getString("name"),
        color = SharedMarkerColor.valueOf(getString("color")),
        tags = tags,
        notes = getString("notes"),
        createdBy = SharedMarkerActor(
            getObject("created_by_user_id", UUID::class.java),
            getString("created_display_name"),
        ),
        updatedBy = SharedMarkerActor(
            getObject("updated_by_user_id", UUID::class.java),
            getString("updated_display_name"),
        ),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        version = getLong("version"),
    )
}

private fun SharedMarker.toProtocolJson(): JsonObject = buildJsonObject {
    put("markerId", markerId.toString())
    put("workspaceId", workspaceId.toString())
    put("systemId", systemId)
    put("name", name)
    put("color", color.name)
    put("tags", JsonArray(tags.map(::JsonPrimitive)))
    if (notes == null) put("notes", kotlinx.serialization.json.JsonNull) else put("notes", notes)
    put("createdBy", buildJsonObject {
        put("userId", createdBy.userId.toString())
        put("displayName", createdBy.displayName)
    })
    put("updatedBy", buildJsonObject {
        put("userId", updatedBy.userId.toString())
        put("displayName", updatedBy.displayName)
    })
    put("createdAt", createdAt.toString())
    put("updatedAt", updatedAt.toString())
    put("version", version)
}

private fun PreparedStatement.setTextArray(index: Int, connection: Connection, values: List<String>) {
    val array = connection.createArrayOf("text", values.toTypedArray())
    setArray(index, array)
}

private fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

private fun PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC))
}
