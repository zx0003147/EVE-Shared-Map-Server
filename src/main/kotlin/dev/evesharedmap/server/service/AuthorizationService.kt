package dev.evesharedmap.server.service

import dev.evesharedmap.server.domain.AuthenticationPrincipal
import dev.evesharedmap.server.domain.WorkspaceCapability
import java.util.UUID

class AuthorizationService {
    fun requireWorkspace(
        principal: AuthenticationPrincipal,
        workspaceId: UUID,
        capability: WorkspaceCapability,
    ): AuthenticationPrincipal {
        if (principal.membership.workspaceId != workspaceId) throw ServiceErrors.notFound()
        if (!principal.membership.role.permits(capability)) {
            val required = when (capability) {
                WorkspaceCapability.READ -> "VIEWER"
                WorkspaceCapability.MARKER_WRITE -> "EDITOR or ADMIN"
                WorkspaceCapability.ADMIN -> "ADMIN"
            }
            throw ServiceErrors.forbidden("$required role is required for this operation.")
        }
        return principal
    }
}
