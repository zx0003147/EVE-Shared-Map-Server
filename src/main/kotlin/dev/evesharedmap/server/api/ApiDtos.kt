package dev.evesharedmap.server.api

import dev.evesharedmap.server.domain.AuthenticationPrincipal
import dev.evesharedmap.server.domain.DeviceTokenMetadata
import dev.evesharedmap.server.domain.InviteMetadata
import dev.evesharedmap.server.domain.MemberRecord
import dev.evesharedmap.server.domain.WorkspaceMembership
import dev.evesharedmap.server.marker.SharedMarker
import dev.evesharedmap.server.marker.SharedMarkerSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class ExchangeInviteRequest(
    val inviteToken: String,
    val deviceName: String,
)

@Serializable
data class CreateMemberRequest(
    val displayName: String,
    val role: String,
)

@Serializable
data class UpdateMemberRequest(
    val expectedVersion: Long,
    val displayName: String? = null,
    val role: String? = null,
)

@Serializable
data class CreateInviteRequest(
    val expiresInHours: Long = 72,
)

@Serializable
data class CreateSharedMarkerRequest(
    val systemId: Int,
    val name: String,
    val color: String,
    val tags: List<String>,
    val notes: String?,
)

@Serializable
data class UpdateSharedMarkerRequest(
    val expectedVersion: Long,
    val name: String,
    val color: String,
    val tags: List<String>,
    val notes: String?,
)

@Serializable
data class UserDto(
    val userId: String,
    val displayName: String,
)

@Serializable
data class WorkspaceDto(
    val workspaceId: String,
    val name: String,
    val role: String,
    val revision: Long,
    val memberId: String,
)

@Serializable
data class DeviceDto(
    val tokenId: String,
    val deviceName: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val expiresAt: String,
    val revokedAt: String? = null,
)

@Serializable
data class ExchangeInviteResponse(
    val accessToken: String,
    val tokenId: String,
    val expiresAt: String,
    val user: UserDto,
    val workspace: WorkspaceDto,
)

@Serializable
data class MeResponse(
    val user: UserDto,
    val workspace: WorkspaceDto,
    val device: DeviceDto,
)

@Serializable
data class WorkspacesResponse(val workspaces: List<WorkspaceDto>)

@Serializable
data class DevicesResponse(val devices: List<DeviceDto>)

@Serializable
data class MemberDto(
    val memberId: String,
    val userId: String,
    val displayName: String,
    val role: String,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
    val revokedAt: String?,
)

@Serializable
data class MembersResponse(val members: List<MemberDto>)

@Serializable
data class InviteCreatedResponse(
    val inviteId: String,
    val inviteToken: String,
    val memberId: String,
    val expiresAt: String,
    val createdAt: String,
)

@Serializable
data class InviteMetadataDto(
    val inviteId: String,
    val workspaceId: String,
    val memberId: String,
    val userId: String,
    val displayName: String,
    val role: String,
    val createdByMemberId: String,
    val createdAt: String,
    val expiresAt: String,
    val usedAt: String?,
    val revokedAt: String?,
    val status: String,
)

@Serializable
data class InvitesResponse(val invites: List<InviteMetadataDto>)

@Serializable
data class SharedMarkerDto(
    val markerId: String,
    val workspaceId: String,
    val systemId: Int,
    val name: String,
    val color: String,
    val tags: List<String>,
    val notes: String?,
    val createdBy: UserDto,
    val updatedBy: UserDto,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class SharedMarkerSnapshotResponse(
    val workspaceId: String,
    val revision: Long,
    val generatedAt: String,
    val markers: List<SharedMarkerDto>,
)

internal fun AuthenticationPrincipal.toMeResponse(): MeResponse = MeResponse(
    user = UserDto(membership.user.userId.toString(), membership.user.displayName),
    workspace = membership.toDto(),
    device = device.toDto(),
)

internal fun WorkspaceMembership.toDto(): WorkspaceDto = WorkspaceDto(
    workspaceId = workspaceId.toString(),
    name = workspaceName,
    role = role.name,
    revision = workspaceRevision,
    memberId = memberId.toString(),
)

internal fun DeviceTokenMetadata.toDto(): DeviceDto = DeviceDto(
    tokenId = tokenId.toString(),
    deviceName = deviceName,
    createdAt = createdAt.toString(),
    lastUsedAt = lastUsedAt?.toString(),
    expiresAt = expiresAt.toString(),
    revokedAt = revokedAt?.toString(),
)

internal fun MemberRecord.toDto(): MemberDto = MemberDto(
    memberId = memberId.toString(),
    userId = userId.toString(),
    displayName = displayName,
    role = role.name,
    version = version,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    revokedAt = revokedAt?.toString(),
)

internal fun InviteMetadata.toDto(): InviteMetadataDto = InviteMetadataDto(
    inviteId = inviteId.toString(),
    workspaceId = workspaceId.toString(),
    memberId = memberId.toString(),
    userId = userId.toString(),
    displayName = displayName,
    role = role.name,
    createdByMemberId = createdByMemberId.toString(),
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString(),
    usedAt = usedAt?.toString(),
    revokedAt = revokedAt?.toString(),
    status = status.name,
)

internal fun SharedMarker.toDto(): SharedMarkerDto = SharedMarkerDto(
    markerId = markerId.toString(),
    workspaceId = workspaceId.toString(),
    systemId = systemId,
    name = name,
    color = color.name,
    tags = tags,
    notes = notes,
    createdBy = UserDto(createdBy.userId.toString(), createdBy.displayName),
    updatedBy = UserDto(updatedBy.userId.toString(), updatedBy.displayName),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    version = version,
)

internal fun SharedMarkerSnapshot.toResponse(): SharedMarkerSnapshotResponse = SharedMarkerSnapshotResponse(
    workspaceId = workspaceId.toString(),
    revision = revision,
    generatedAt = generatedAt.toString(),
    markers = markers.map { it.toDto() },
)
