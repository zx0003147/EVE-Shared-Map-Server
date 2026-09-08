package dev.evesharedmap.server.route

import java.time.Instant
import java.util.UUID

enum class RouteHandoffType { NORMAL, CAPITAL }

data class RouteHandoffMapMetadata(
    val universeBuild: String,
    val plannerVersion: String,
    val webPackVersion: String?,
)

data class RouteHandoffResolvedEdge(
    val fromSystemId: Int,
    val toSystemId: Int,
    val type: String,
    val distanceLy: Double?,
)

data class RouteHandoffDraft(
    val type: RouteHandoffType,
    val originSystemId: Int,
    val waypointSystemIds: List<Int>,
    val destinationSystemId: Int,
    val useAnsiblex: Boolean?,
    val capitalRangeLy: Double?,
    val jumpProfileId: String?,
    val resolvedSystemIds: List<Int>,
    val resolvedEdges: List<RouteHandoffResolvedEdge>,
    val mapMetadata: RouteHandoffMapMetadata,
)

data class RouteHandoff(
    val routeHandoffId: UUID,
    val workspaceId: UUID,
    val publisher: RouteHandoffPublisher,
    val createdAt: Instant,
    val expiresAt: Instant,
    val draft: RouteHandoffDraft,
)

data class RouteHandoffPublisher(
    val memberId: UUID,
    val userId: UUID,
    val displayName: String,
    val deviceTokenId: UUID,
    val deviceName: String,
)
