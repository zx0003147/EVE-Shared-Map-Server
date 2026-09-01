package dev.evesharedmap.server.marker

import java.time.Instant
import java.util.UUID

enum class SharedMarkerColor {
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    BLUE,
    PURPLE,
    WHITE,
}

data class SharedMarkerActor(
    val userId: UUID,
    val displayName: String,
)

data class SharedMarker(
    val markerId: UUID,
    val workspaceId: UUID,
    val systemId: Int,
    val name: String,
    val color: SharedMarkerColor,
    val tags: List<String>,
    val notes: String?,
    val createdBy: SharedMarkerActor,
    val updatedBy: SharedMarkerActor,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class SharedMarkerSnapshot(
    val workspaceId: UUID,
    val revision: Long,
    val generatedAt: Instant,
    val markers: List<SharedMarker>,
)

data class SharedMarkerFields(
    val name: String,
    val color: SharedMarkerColor,
    val tags: List<String>,
    val notes: String?,
)
