package dev.evesharedmap.server.domain

import java.time.Instant
import java.util.UUID

enum class WorkspaceRole {
    VIEWER,
    EDITOR,
    ADMIN;

    fun permits(capability: WorkspaceCapability): Boolean = when (capability) {
        WorkspaceCapability.READ -> true
        WorkspaceCapability.MARKER_WRITE -> this == EDITOR || this == ADMIN
        WorkspaceCapability.ADMIN -> this == ADMIN
    }
}

enum class WorkspaceCapability {
    READ,
    MARKER_WRITE,
    ADMIN,
}

data class UserIdentity(
    val userId: UUID,
    val displayName: String,
)

data class WorkspaceMembership(
    val memberId: UUID,
    val workspaceId: UUID,
    val workspaceName: String,
    val workspaceRevision: Long,
    val user: UserIdentity,
    val role: WorkspaceRole,
    val version: Long,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

data class DeviceTokenMetadata(
    val tokenId: UUID,
    val memberId: UUID,
    val deviceName: String,
    val tokenPrefix: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class AuthenticationPrincipal(
    val tokenId: UUID,
    val membership: WorkspaceMembership,
    val device: DeviceTokenMetadata,
)

data class MemberRecord(
    val memberId: UUID,
    val workspaceId: UUID,
    val userId: UUID,
    val displayName: String,
    val role: WorkspaceRole,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant?,
)

enum class InviteStatus {
    ACTIVE,
    USED,
    REVOKED,
    EXPIRED,
}

data class InviteMetadata(
    val inviteId: UUID,
    val workspaceId: UUID,
    val memberId: UUID,
    val userId: UUID,
    val displayName: String,
    val role: WorkspaceRole,
    val createdByMemberId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val revokedAt: Instant?,
    val status: InviteStatus,
)

data class IssuedInvite(
    val metadata: InviteMetadata,
    val rawSecret: String,
)

data class IssuedDeviceToken(
    val principal: AuthenticationPrincipal,
    val rawSecret: String,
)

data class BootstrapResult(
    val userId: UUID,
    val workspaceId: UUID,
    val memberId: UUID,
    val inviteId: UUID,
    val inviteExpiresAt: Instant,
    val rawInviteSecret: String,
)
