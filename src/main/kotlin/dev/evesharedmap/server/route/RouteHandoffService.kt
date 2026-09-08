package dev.evesharedmap.server.route

import dev.evesharedmap.server.domain.AuthenticationPrincipal
import dev.evesharedmap.server.service.ServiceErrors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class RouteHandoffService(
    private val dataSource: DataSource,
    private val validation: RouteHandoffValidation,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun listRecent(workspaceId: UUID): List<RouteHandoff> = dataSource.connection.use { connection ->
        connection.prepareStatement(SELECT_HANDOFFS + " WHERE h.workspace_id = ? AND h.expires_at > ?" + ORDER_LIMIT).use {
            it.setObject(1, workspaceId)
            it.setInstant(2, clock.instant())
            it.executeQuery().use { result -> buildList { while (result.next()) add(result.toRouteHandoff()) } }
        }
    }

    fun publish(
        connection: Connection,
        actor: AuthenticationPrincipal,
        draft: RouteHandoffDraft,
        requestId: String,
    ): RouteHandoff {
        val validated = validation.validate(draft)
        val now = clock.instant()
        val expiresAt = now.plus(ROUTE_HANDOFF_TTL)
        connection.cleanupExpiredRouteHandoffs(now)
        val handoffId = connection.prepareStatement(
            """
            INSERT INTO route_handoffs (
                workspace_id, publisher_member_id, publisher_user_id, publisher_token_id,
                route_type, origin_system_id, destination_system_id,
                intent, resolved_snapshot, map_metadata, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
            RETURNING route_handoff_id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, actor.membership.workspaceId)
            statement.setObject(2, actor.membership.memberId)
            statement.setObject(3, actor.membership.user.userId)
            statement.setObject(4, actor.tokenId)
            statement.setString(5, validated.type.name)
            statement.setInt(6, validated.originSystemId)
            statement.setInt(7, validated.destinationSystemId)
            statement.setString(8, validated.intentJson().toString())
            statement.setString(9, validated.resolvedJson().toString())
            statement.setString(10, validated.mapMetadataJson().toString())
            statement.setInstant(11, now)
            statement.setInstant(12, expiresAt)
            statement.executeQuery().use { result -> result.next(); result.getObject(1, UUID::class.java) }
        }
        connection.trimRouteHandoffHistory(actor.membership.workspaceId)
        connection.prepareStatement(
            """
            INSERT INTO audit_events (
                workspace_id, actor_user_id, actor_display_name, action, target_type,
                target_id, system_id, timestamp, metadata
            ) VALUES (?, ?, ?, 'ROUTE_HANDOFF_PUBLISHED', 'ROUTE_HANDOFF', ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, actor.membership.workspaceId)
            statement.setObject(2, actor.membership.user.userId)
            statement.setString(3, actor.membership.user.displayName)
            statement.setObject(4, handoffId)
            statement.setInt(5, validated.originSystemId)
            statement.setInstant(6, now)
            statement.setString(7, buildJsonObject {
                put("requestId", requestId)
                put("routeType", validated.type.name)
                put("destinationSystemId", validated.destinationSystemId)
                put("resolvedSystemCount", validated.resolvedSystemIds.size)
            }.toString())
            check(statement.executeUpdate() == 1)
        }
        return connection.findRouteHandoff(actor.membership.workspaceId, handoffId)
            ?: error("Newly published Route Handoff disappeared inside its transaction.")
    }

    internal companion object {
        val ROUTE_HANDOFF_TTL: Duration = Duration.ofDays(7)
        const val MAX_ROUTE_HANDOFFS_PER_WORKSPACE = 20
        const val SELECT_HANDOFFS = """
            SELECT h.route_handoff_id, h.workspace_id, h.route_type, h.origin_system_id,
                   h.destination_system_id, h.intent::text, h.resolved_snapshot::text,
                   h.map_metadata::text, h.created_at, h.expires_at,
                   h.publisher_member_id, h.publisher_user_id, u.display_name,
                   h.publisher_token_id, t.device_name
            FROM route_handoffs h
            JOIN users u ON u.user_id = h.publisher_user_id
            JOIN access_tokens t ON t.token_id = h.publisher_token_id
        """
        const val ORDER_LIMIT = " ORDER BY h.created_at DESC, h.route_handoff_id DESC LIMIT 20"
    }
}

private fun Connection.findRouteHandoff(workspaceId: UUID, handoffId: UUID): RouteHandoff? = prepareStatement(
    RouteHandoffService.SELECT_HANDOFFS + " WHERE h.workspace_id = ? AND h.route_handoff_id = ?",
).use {
    it.setObject(1, workspaceId)
    it.setObject(2, handoffId)
    it.executeQuery().use { result -> if (result.next()) result.toRouteHandoff() else null }
}

private fun Connection.cleanupExpiredRouteHandoffs(now: Instant) {
    prepareStatement("DELETE FROM route_handoffs WHERE expires_at <= ?").use {
        it.setInstant(1, now)
        it.executeUpdate()
    }
}

private fun Connection.trimRouteHandoffHistory(workspaceId: UUID) {
    prepareStatement(
        """
        DELETE FROM route_handoffs WHERE route_handoff_id IN (
            SELECT route_handoff_id FROM route_handoffs
            WHERE workspace_id = ? ORDER BY created_at DESC, route_handoff_id DESC
            OFFSET ${RouteHandoffService.MAX_ROUTE_HANDOFFS_PER_WORKSPACE}
        )
        """.trimIndent(),
    ).use {
        it.setObject(1, workspaceId)
        it.executeUpdate()
    }
}

private fun ResultSet.toRouteHandoff(): RouteHandoff {
    val intent = Json.parseToJsonElement(getString("intent")).jsonObject
    val resolved = Json.parseToJsonElement(getString("resolved_snapshot")).jsonObject
    val map = Json.parseToJsonElement(getString("map_metadata")).jsonObject
    val type = RouteHandoffType.valueOf(getString("route_type"))
    return RouteHandoff(
        routeHandoffId = getObject("route_handoff_id", UUID::class.java),
        workspaceId = getObject("workspace_id", UUID::class.java),
        publisher = RouteHandoffPublisher(
            memberId = getObject("publisher_member_id", UUID::class.java),
            userId = getObject("publisher_user_id", UUID::class.java),
            displayName = getString("display_name"),
            deviceTokenId = getObject("publisher_token_id", UUID::class.java),
            deviceName = getString("device_name"),
        ),
        createdAt = getTimestamp("created_at").toInstant(),
        expiresAt = getTimestamp("expires_at").toInstant(),
        draft = RouteHandoffDraft(
            type = type,
            originSystemId = getInt("origin_system_id"),
            waypointSystemIds = intent.getValue("waypointSystemIds").jsonArray.map { it.jsonPrimitive.int },
            destinationSystemId = getInt("destination_system_id"),
            useAnsiblex = intent["useAnsiblex"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean,
            capitalRangeLy = intent["capitalRangeLy"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double,
            jumpProfileId = intent["jumpProfileId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            resolvedSystemIds = resolved.getValue("systemIds").jsonArray.map { it.jsonPrimitive.int },
            resolvedEdges = resolved.getValue("edges").jsonArray.map { edge ->
                val value = edge.jsonObject
                RouteHandoffResolvedEdge(
                    value.getValue("fromSystemId").jsonPrimitive.int,
                    value.getValue("toSystemId").jsonPrimitive.int,
                    value.getValue("type").jsonPrimitive.content,
                    value["distanceLy"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double,
                )
            },
            mapMetadata = RouteHandoffMapMetadata(
                map.getValue("universeBuild").jsonPrimitive.content,
                map.getValue("plannerVersion").jsonPrimitive.content,
                map["webPackVersion"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            ),
        ),
    )
}

private fun RouteHandoffDraft.intentJson(): JsonObject = buildJsonObject {
    put("waypointSystemIds", buildJsonArray { waypointSystemIds.forEach { add(JsonPrimitive(it)) } })
    if (useAnsiblex == null) put("useAnsiblex", JsonNull) else put("useAnsiblex", useAnsiblex)
    if (capitalRangeLy == null) put("capitalRangeLy", JsonNull) else put("capitalRangeLy", capitalRangeLy)
    if (jumpProfileId == null) put("jumpProfileId", JsonNull) else put("jumpProfileId", jumpProfileId)
}

private fun RouteHandoffDraft.resolvedJson(): JsonObject = buildJsonObject {
    put("systemIds", buildJsonArray { resolvedSystemIds.forEach { add(JsonPrimitive(it)) } })
    put("edges", buildJsonArray {
        resolvedEdges.forEach { edge ->
            add(buildJsonObject {
                put("fromSystemId", edge.fromSystemId)
                put("toSystemId", edge.toSystemId)
                put("type", edge.type)
                if (edge.distanceLy == null) put("distanceLy", JsonNull) else put("distanceLy", edge.distanceLy)
            })
        }
    })
}

private fun RouteHandoffDraft.mapMetadataJson(): JsonObject = buildJsonObject {
    put("universeBuild", mapMetadata.universeBuild)
    put("plannerVersion", mapMetadata.plannerVersion)
    if (mapMetadata.webPackVersion == null) put("webPackVersion", JsonNull) else put("webPackVersion", mapMetadata.webPackVersion)
}

private fun java.sql.PreparedStatement.setInstant(index: Int, instant: Instant) {
    setObject(index, instant.atOffset(java.time.ZoneOffset.UTC))
}
