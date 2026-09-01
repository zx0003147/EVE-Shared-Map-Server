package dev.evesharedmap.server.service

import dev.evesharedmap.server.domain.AuthenticationPrincipal
import dev.evesharedmap.server.domain.BootstrapResult
import dev.evesharedmap.server.domain.DeviceTokenMetadata
import dev.evesharedmap.server.domain.InviteMetadata
import dev.evesharedmap.server.domain.InviteStatus
import dev.evesharedmap.server.domain.IssuedDeviceToken
import dev.evesharedmap.server.domain.IssuedInvite
import dev.evesharedmap.server.domain.MemberRecord
import dev.evesharedmap.server.domain.TextValidation
import dev.evesharedmap.server.domain.UserIdentity
import dev.evesharedmap.server.domain.WorkspaceMembership
import dev.evesharedmap.server.domain.WorkspaceCapability
import dev.evesharedmap.server.domain.WorkspaceRole
import dev.evesharedmap.server.domain.FieldValidationException
import dev.evesharedmap.server.security.CredentialGenerator
import dev.evesharedmap.server.security.CredentialHasher
import dev.evesharedmap.server.security.CredentialKind
import dev.evesharedmap.server.security.SecureCredentialGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

data class MutationResponse(
    val status: Int,
    val responseBody: JsonElement?,
    val storageBody: JsonElement? = responseBody,
)

data class IdempotentMutationResult(
    val response: MutationResponse,
    val replayed: Boolean,
)

class SharedMapService(
    private val dataSource: DataSource,
    private val hasher: CredentialHasher,
    private val credentialGenerator: CredentialGenerator = SecureCredentialGenerator(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun bootstrapAdmin(
        displayName: String,
        workspaceName: String,
        inviteLifetime: Duration,
    ): BootstrapResult {
        val normalizedDisplayName = validate { TextValidation.displayName(displayName) }
        val normalizedWorkspaceName = validate { TextValidation.workspaceName(workspaceName) }
        if (inviteLifetime < MIN_INVITE_LIFETIME || inviteLifetime > MAX_INVITE_LIFETIME) {
            throw ServiceErrors.invalid("inviteTtl", "OUT_OF_RANGE")
        }
        return transaction { connection ->
            connection.createStatement().use { it.execute("LOCK TABLE workspaces IN EXCLUSIVE MODE") }
            val workspaceCount = connection.prepareStatement("SELECT count(*) FROM workspaces").use { statement ->
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
            if (workspaceCount != 0L) {
                throw ServiceException(409, "BOOTSTRAP_ALREADY_COMPLETED", "Bootstrap has already been completed.")
            }

            val now = clock.instant()
            val userId = connection.insertUser(normalizedDisplayName, now)
            val workspaceId = connection.insertWorkspace(normalizedWorkspaceName, now)
            val memberId = connection.insertMember(workspaceId, userId, WorkspaceRole.ADMIN, now)
            val rawInvite = credentialGenerator.generate(CredentialKind.INVITE)
            val inviteHash = hasher.hash(rawInvite)
            val inviteExpiresAt = now.plus(inviteLifetime)
            val inviteId = try {
                connection.insertInvite(
                    memberId = memberId,
                    createdByMemberId = memberId,
                    secretHash = inviteHash,
                    secretPrefix = CredentialHasher.operatorPrefix(rawInvite),
                    createdAt = now,
                    expiresAt = inviteExpiresAt,
                )
            } finally {
                inviteHash.fill(0)
            }

            connection.insertAudit(
                workspaceId,
                userId,
                normalizedDisplayName,
                "BOOTSTRAP_ADMIN_CREATED",
                "WORKSPACE",
                workspaceId,
                now,
                buildJsonObject {
                    put("memberId", memberId.toString())
                    put("inviteId", inviteId.toString())
                },
            )
            connection.insertAudit(
                workspaceId,
                userId,
                normalizedDisplayName,
                "INVITE_CREATED",
                "INVITE",
                inviteId,
                now,
                buildJsonObject { put("memberId", memberId.toString()) },
            )

            BootstrapResult(
                userId = userId,
                workspaceId = workspaceId,
                memberId = memberId,
                inviteId = inviteId,
                inviteExpiresAt = inviteExpiresAt,
                rawInviteSecret = rawInvite,
            )
        }
    }

    fun authenticate(rawToken: String): AuthenticationPrincipal {
        if (!CredentialHasher.isWellFormed(rawToken, CredentialKind.DEVICE)) {
            throw ServiceErrors.unauthenticated()
        }
        val prefix = CredentialHasher.operatorPrefix(rawToken)
        val now = clock.instant()
        dataSource.connection.use { connection ->
            connection.prepareStatement(AUTHENTICATION_QUERY).use { statement ->
                statement.setString(1, prefix)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val storedHash = result.getBytes("token_hash")
                        val matches = try {
                            hasher.matches(storedHash, rawToken)
                        } finally {
                            storedHash.fill(0)
                        }
                        if (!matches) continue
                        if (result.instantOrNull("token_revoked_at") != null) throw ServiceErrors.tokenRevoked()
                        if (!result.getTimestamp("token_expires_at").toInstant().isAfter(now)) {
                            throw ServiceErrors.tokenExpired()
                        }
                        if (result.instantOrNull("member_revoked_at") != null || result.getString("user_status") != "ACTIVE") {
                            throw ServiceErrors.forbidden("The Workspace membership is no longer active.")
                        }

                        val principal = result.toPrincipal()
                        val lastUsedAt = principal.device.lastUsedAt
                        if (lastUsedAt == null || lastUsedAt.plus(LAST_USED_UPDATE_INTERVAL).isBefore(now)) {
                            connection.prepareStatement(
                                "UPDATE access_tokens SET last_used_at = ? WHERE token_id = ? AND revoked_at IS NULL",
                            ).use { update ->
                                update.setInstant(1, now)
                                update.setObject(2, principal.tokenId)
                                update.executeUpdate()
                            }
                            return principal.copy(device = principal.device.copy(lastUsedAt = now))
                        }
                        return principal
                    }
                }
            }
        }
        throw ServiceErrors.unauthenticated()
    }

    fun exchangeInvite(
        rawInvite: String,
        deviceName: String,
        requestId: String,
    ): IssuedDeviceToken {
        if (!CredentialHasher.isWellFormed(rawInvite, CredentialKind.INVITE)) {
            throw ServiceErrors.inviteInvalid()
        }
        val normalizedDeviceName = validate { TextValidation.deviceName(deviceName) }
        val prefix = CredentialHasher.operatorPrefix(rawInvite)
        return transaction { connection ->
            val now = clock.instant()
            val invite = connection.findInviteForExchange(prefix, rawInvite)
                ?: throw ServiceErrors.inviteInvalid()
            if (invite.revokedAt != null) throw ServiceErrors.inviteRevoked()
            if (invite.usedAt != null) throw ServiceErrors.inviteUsed()
            if (!invite.expiresAt.isAfter(now)) throw ServiceErrors.inviteExpired()
            if (invite.memberRevokedAt != null || invite.userStatus != "ACTIVE") {
                throw ServiceErrors.inviteInvalid()
            }
            val activeTokens = connection.countActiveTokens(invite.memberId, now)
            if (activeTokens >= MAX_ACTIVE_TOKENS) {
                throw ServiceException(422, "INVALID_ARGUMENT", "The membership already has the maximum active devices.")
            }

            val rawToken = credentialGenerator.generate(CredentialKind.DEVICE)
            val tokenHash = hasher.hash(rawToken)
            val expiresAt = now.plus(DEVICE_TOKEN_LIFETIME)
            val tokenId = try {
                connection.insertAccessToken(
                    memberId = invite.memberId,
                    tokenHash = tokenHash,
                    tokenPrefix = CredentialHasher.operatorPrefix(rawToken),
                    deviceName = normalizedDeviceName,
                    createdAt = now,
                    expiresAt = expiresAt,
                )
            } finally {
                tokenHash.fill(0)
            }
            connection.prepareStatement(
                "UPDATE invites SET used_at = ? WHERE invite_id = ? AND used_at IS NULL",
            ).use { statement ->
                statement.setInstant(1, now)
                statement.setObject(2, invite.inviteId)
                check(statement.executeUpdate() == 1) { "Invite row lock was lost." }
            }
            connection.insertAudit(
                invite.workspaceId,
                invite.userId,
                invite.displayName,
                "INVITE_EXCHANGED",
                "INVITE",
                invite.inviteId,
                now,
                buildJsonObject {
                    put("requestId", requestId)
                    put("tokenId", tokenId.toString())
                },
            )
            connection.insertAudit(
                invite.workspaceId,
                invite.userId,
                invite.displayName,
                "DEVICE_TOKEN_CREATED",
                "DEVICE",
                tokenId,
                now,
                buildJsonObject {
                    put("requestId", requestId)
                    put("memberId", invite.memberId.toString())
                },
            )

            val membership = WorkspaceMembership(
                memberId = invite.memberId,
                workspaceId = invite.workspaceId,
                workspaceName = invite.workspaceName,
                workspaceRevision = invite.workspaceRevision,
                user = UserIdentity(invite.userId, invite.displayName),
                role = invite.role,
                version = invite.memberVersion,
                createdAt = invite.memberCreatedAt,
                revokedAt = null,
            )
            val device = DeviceTokenMetadata(
                tokenId = tokenId,
                memberId = invite.memberId,
                deviceName = normalizedDeviceName,
                tokenPrefix = CredentialHasher.operatorPrefix(rawToken),
                createdAt = now,
                lastUsedAt = null,
                expiresAt = expiresAt,
                revokedAt = null,
            )
            IssuedDeviceToken(AuthenticationPrincipal(tokenId, membership, device), rawToken)
        }
    }

    fun listMembers(workspaceId: UUID): List<MemberRecord> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT m.member_id, m.workspace_id, m.user_id, u.display_name, m.role, m.version,
                   m.created_at, m.updated_at, m.revoked_at
            FROM workspace_members m
            JOIN users u ON u.user_id = m.user_id
            WHERE m.workspace_id = ?
            ORDER BY m.created_at, m.member_id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, workspaceId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toMember()) } }
        }
    }

    fun listInvites(workspaceId: UUID): List<InviteMetadata> = dataSource.connection.use { connection ->
        connection.prepareStatement(INVITE_LIST_QUERY).use { statement ->
            statement.setObject(1, workspaceId)
            statement.executeQuery().use { result ->
                val now = clock.instant()
                buildList { while (result.next()) add(result.toInviteMetadata(now)) }
            }
        }
    }

    fun listDevices(memberId: UUID): List<DeviceTokenMetadata> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT token_id, member_id, device_name, token_prefix, created_at, last_used_at, expires_at, revoked_at
            FROM access_tokens WHERE member_id = ? ORDER BY created_at, token_id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, memberId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toDevice()) } }
        }
    }

    fun executeIdempotent(
        tokenId: UUID,
        idempotencyKey: UUID,
        fingerprint: ByteArray,
        requiredCapability: WorkspaceCapability,
        nonReplayableSecretResponse: Boolean = false,
        operation: (Connection) -> MutationResponse,
    ): IdempotentMutationResult = transaction { connection ->
        val now = clock.instant()
        connection.revalidateMutationAuthority(tokenId, now, requiredCapability)
        connection.cleanupExpiredIdempotency(now)
        val inserted = connection.prepareStatement(
            """
            INSERT INTO idempotency_records (
                token_id, idempotency_key, request_fingerprint, state, created_at, expires_at
            ) VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)
            ON CONFLICT (token_id, idempotency_key) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, tokenId)
            statement.setObject(2, idempotencyKey)
            statement.setBytes(3, fingerprint)
            statement.setInstant(4, now)
            statement.setInstant(5, now.plus(IDEMPOTENCY_LIFETIME))
            statement.executeUpdate() == 1
        }

        if (!inserted) {
            val existing = connection.prepareStatement(
                """
                SELECT request_fingerprint, state, response_status, response_body::text
                FROM idempotency_records
                WHERE token_id = ? AND idempotency_key = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, tokenId)
                statement.setObject(2, idempotencyKey)
                statement.executeQuery().use { result ->
                    check(result.next()) { "Conflicting idempotency record disappeared." }
                    StoredIdempotency(
                        fingerprint = result.getBytes(1),
                        state = result.getString(2),
                        status = result.getInt(3),
                        body = result.getString(4)?.let { Json.parseToJsonElement(it) },
                    )
                }
            }
            val fingerprintMatches = try {
                MessageDigest.isEqual(existing.fingerprint, fingerprint)
            } finally {
                existing.fingerprint.fill(0)
            }
            if (!fingerprintMatches) {
                throw ServiceException(
                    409,
                    "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used for a different request.",
                )
            }
            check(existing.state == "COMPLETED") { "Idempotency record did not complete under its row lock." }
            if (nonReplayableSecretResponse) {
                throw ServiceException(
                    409,
                    "IDEMPOTENCY_RESPONSE_NOT_REPLAYABLE",
                    "The mutation previously succeeded, but its one-time secret response cannot safely be replayed.",
                    existing.body as? JsonObject,
                )
            }
            return@transaction IdempotentMutationResult(
                MutationResponse(existing.status, existing.body),
                replayed = true,
            )
        }

        val response = operation(connection)
        connection.prepareStatement(
            """
            UPDATE idempotency_records
            SET state = 'COMPLETED', response_status = ?, response_body = CAST(? AS jsonb)
            WHERE token_id = ? AND idempotency_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, response.status)
            if (response.storageBody == null) statement.setNull(2, Types.VARCHAR)
            else statement.setString(2, response.storageBody.toString())
            statement.setObject(3, tokenId)
            statement.setObject(4, idempotencyKey)
            check(statement.executeUpdate() == 1)
        }
        IdempotentMutationResult(response, replayed = false)
    }

    fun createMember(
        connection: Connection,
        actor: AuthenticationPrincipal,
        displayName: String,
        role: WorkspaceRole,
        requestId: String,
    ): MemberRecord {
        val normalizedName = validate { TextValidation.displayName(displayName) }
        val now = clock.instant()
        val userId = connection.insertUser(normalizedName, now)
        val memberId = connection.insertMember(actor.membership.workspaceId, userId, role, now)
        connection.insertAudit(
            actor.membership.workspaceId,
            actor.membership.user.userId,
            actor.membership.user.displayName,
            "USER_CREATED",
            "MEMBER",
            memberId,
            now,
            buildJsonObject {
                put("requestId", requestId)
                put("userId", userId.toString())
            },
        )
        connection.insertAudit(
            actor.membership.workspaceId,
            actor.membership.user.userId,
            actor.membership.user.displayName,
            "MEMBER_CREATED",
            "MEMBER",
            memberId,
            now,
            buildJsonObject {
                put("requestId", requestId)
                put("role", role.name)
            },
        )
        return MemberRecord(
            memberId,
            actor.membership.workspaceId,
            userId,
            normalizedName,
            role,
            1,
            now,
            now,
            null,
        )
    }

    fun updateMember(
        connection: Connection,
        actor: AuthenticationPrincipal,
        memberId: UUID,
        expectedVersion: Long,
        displayName: String?,
        role: WorkspaceRole?,
        requestId: String,
    ): MemberRecord {
        if (expectedVersion <= 0) throw ServiceErrors.invalid("expectedVersion", "MUST_BE_POSITIVE")
        if (displayName == null && role == null) throw ServiceErrors.invalid("request", "NO_CHANGES")
        connection.lockWorkspaceAuthorization(actor.membership.workspaceId)
        val current = connection.findMemberForUpdate(actor.membership.workspaceId, memberId)
            ?: throw ServiceErrors.notFound()
        if (current.revokedAt != null) throw ServiceErrors.notFound()
        if (current.version != expectedVersion) throw ServiceErrors.memberVersionConflict()
        if (current.role == WorkspaceRole.ADMIN && role != null && role != WorkspaceRole.ADMIN) {
            connection.requireAnotherAdmin(actor.membership.workspaceId, memberId)
        }
        val normalizedName = displayName?.let { validate { TextValidation.displayName(it) } }
        val nextName = normalizedName ?: current.displayName
        val nextRole = role ?: current.role
        val now = clock.instant()
        if (normalizedName != null) {
            connection.prepareStatement("UPDATE users SET display_name = ?, updated_at = ? WHERE user_id = ?").use {
                it.setString(1, normalizedName)
                it.setInstant(2, now)
                it.setObject(3, current.userId)
                check(it.executeUpdate() == 1)
            }
        }
        connection.prepareStatement(
            """
            UPDATE workspace_members SET role = ?, version = version + 1, updated_at = ?
            WHERE member_id = ? AND version = ? AND revoked_at IS NULL
            """.trimIndent(),
        ).use {
            it.setString(1, nextRole.name)
            it.setInstant(2, now)
            it.setObject(3, memberId)
            it.setLong(4, expectedVersion)
            if (it.executeUpdate() != 1) throw ServiceErrors.memberVersionConflict()
        }
        if (normalizedName != null && normalizedName != current.displayName) {
            connection.insertAudit(
                actor.membership.workspaceId,
                actor.membership.user.userId,
                actor.membership.user.displayName,
                "MEMBER_RENAMED",
                "MEMBER",
                memberId,
                now,
                buildJsonObject {
                    put("requestId", requestId)
                    put("changedField", "displayName")
                },
            )
        }
        if (nextRole != current.role) {
            connection.insertAudit(
                actor.membership.workspaceId,
                actor.membership.user.userId,
                actor.membership.user.displayName,
                "MEMBER_ROLE_CHANGED",
                "MEMBER",
                memberId,
                now,
                buildJsonObject {
                    put("requestId", requestId)
                    put("fromRole", current.role.name)
                    put("toRole", nextRole.name)
                },
            )
        }
        return current.copy(
            displayName = nextName,
            role = nextRole,
            version = current.version + 1,
            updatedAt = now,
        )
    }

    fun revokeMember(
        connection: Connection,
        actor: AuthenticationPrincipal,
        memberId: UUID,
        expectedVersion: Long,
        requestId: String,
    ) {
        if (expectedVersion <= 0) throw ServiceErrors.invalid("expectedVersion", "MUST_BE_POSITIVE")
        connection.lockWorkspaceAuthorization(actor.membership.workspaceId)
        val current = connection.findMemberForUpdate(actor.membership.workspaceId, memberId)
            ?: throw ServiceErrors.notFound()
        if (current.revokedAt != null) throw ServiceErrors.notFound()
        if (current.version != expectedVersion) throw ServiceErrors.memberVersionConflict()
        if (current.role == WorkspaceRole.ADMIN) {
            connection.requireAnotherAdmin(actor.membership.workspaceId, memberId)
        }
        val now = clock.instant()
        connection.prepareStatement(
            """
            UPDATE workspace_members
            SET revoked_at = ?, updated_at = ?, version = version + 1
            WHERE member_id = ? AND version = ? AND revoked_at IS NULL
            """.trimIndent(),
        ).use {
            it.setInstant(1, now)
            it.setInstant(2, now)
            it.setObject(3, memberId)
            it.setLong(4, expectedVersion)
            if (it.executeUpdate() != 1) throw ServiceErrors.memberVersionConflict()
        }
        val revokedTokens = connection.prepareStatement(
            """
            UPDATE access_tokens SET revoked_at = ?, revoked_by_user_id = ?
            WHERE member_id = ? AND revoked_at IS NULL
            RETURNING token_id
            """.trimIndent(),
        ).use {
            it.setInstant(1, now)
            it.setObject(2, actor.membership.user.userId)
            it.setObject(3, memberId)
            it.executeQuery().use { result -> buildList { while (result.next()) add(result.getObject(1, UUID::class.java)) } }
        }
        connection.insertAudit(
            actor.membership.workspaceId,
            actor.membership.user.userId,
            actor.membership.user.displayName,
            "MEMBER_REVOKED",
            "MEMBER",
            memberId,
            now,
            buildJsonObject {
                put("requestId", requestId)
                put("revokedDeviceCount", revokedTokens.size)
            },
        )
        revokedTokens.forEach { tokenId ->
            connection.insertAudit(
                actor.membership.workspaceId,
                actor.membership.user.userId,
                actor.membership.user.displayName,
                "DEVICE_TOKEN_REVOKED",
                "DEVICE",
                tokenId,
                now,
                buildJsonObject {
                    put("requestId", requestId)
                    put("reason", "MEMBERSHIP_REVOKED")
                },
            )
        }
    }

    fun createInvite(
        connection: Connection,
        actor: AuthenticationPrincipal,
        memberId: UUID,
        lifetime: Duration,
        requestId: String,
    ): IssuedInvite {
        if (lifetime < MIN_INVITE_LIFETIME || lifetime > MAX_INVITE_LIFETIME) {
            throw ServiceErrors.invalid("expiresInHours", "OUT_OF_RANGE")
        }
        val member = connection.findMemberForUpdate(actor.membership.workspaceId, memberId)
            ?: throw ServiceErrors.notFound()
        if (member.revokedAt != null) throw ServiceErrors.notFound()
        val now = clock.instant()
        val activeInvites = connection.prepareStatement(
            """
            SELECT count(*) FROM invites
            WHERE member_id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?
            """.trimIndent(),
        ).use {
            it.setObject(1, memberId)
            it.setInstant(2, now)
            it.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
        if (activeInvites >= MAX_ACTIVE_INVITES) {
            throw ServiceException(422, "INVALID_ARGUMENT", "The membership already has the maximum active invites.")
        }
        val rawSecret = credentialGenerator.generate(CredentialKind.INVITE)
        val secretHash = hasher.hash(rawSecret)
        val expiresAt = now.plus(lifetime)
        val inviteId = try {
            connection.insertInvite(
                memberId,
                actor.membership.memberId,
                secretHash,
                CredentialHasher.operatorPrefix(rawSecret),
                now,
                expiresAt,
            )
        } finally {
            secretHash.fill(0)
        }
        connection.insertAudit(
            actor.membership.workspaceId,
            actor.membership.user.userId,
            actor.membership.user.displayName,
            "INVITE_CREATED",
            "INVITE",
            inviteId,
            now,
            buildJsonObject {
                put("requestId", requestId)
                put("memberId", memberId.toString())
            },
        )
        return IssuedInvite(
            InviteMetadata(
                inviteId,
                actor.membership.workspaceId,
                memberId,
                member.userId,
                member.displayName,
                member.role,
                actor.membership.memberId,
                now,
                expiresAt,
                null,
                null,
                InviteStatus.ACTIVE,
            ),
            rawSecret,
        )
    }

    fun revokeInvite(
        connection: Connection,
        actor: AuthenticationPrincipal,
        inviteId: UUID,
        requestId: String,
    ) {
        val now = clock.instant()
        val row = connection.prepareStatement(
            """
            SELECT i.used_at, i.revoked_at
            FROM invites i
            JOIN workspace_members m ON m.member_id = i.member_id
            WHERE i.invite_id = ? AND m.workspace_id = ?
            FOR UPDATE OF i
            """.trimIndent(),
        ).use {
            it.setObject(1, inviteId)
            it.setObject(2, actor.membership.workspaceId)
            it.executeQuery().use { result ->
                if (!result.next()) null else result.instantOrNull(1) to result.instantOrNull(2)
            }
        } ?: throw ServiceErrors.notFound()
        if (row.first != null) throw ServiceErrors.inviteUsed()
        if (row.second == null) {
            connection.prepareStatement("UPDATE invites SET revoked_at = ? WHERE invite_id = ?").use {
                it.setInstant(1, now)
                it.setObject(2, inviteId)
                check(it.executeUpdate() == 1)
            }
            connection.insertAudit(
                actor.membership.workspaceId,
                actor.membership.user.userId,
                actor.membership.user.displayName,
                "INVITE_REVOKED",
                "INVITE",
                inviteId,
                now,
                buildJsonObject { put("requestId", requestId) },
            )
        }
    }

    fun revokeDevice(
        connection: Connection,
        actor: AuthenticationPrincipal,
        memberId: UUID,
        tokenId: UUID,
        requestId: String,
    ) {
        val now = clock.instant()
        val found = connection.prepareStatement(
            """
            SELECT t.revoked_at
            FROM access_tokens t
            JOIN workspace_members m ON m.member_id = t.member_id
            WHERE t.token_id = ? AND t.member_id = ? AND m.workspace_id = ?
            FOR UPDATE OF t
            """.trimIndent(),
        ).use {
            it.setObject(1, tokenId)
            it.setObject(2, memberId)
            it.setObject(3, actor.membership.workspaceId)
            it.executeQuery().use { result -> if (!result.next()) null else DeviceLock(result.instantOrNull(1)) }
        } ?: throw ServiceErrors.notFound()
        if (found.revokedAt == null) {
            connection.prepareStatement(
                "UPDATE access_tokens SET revoked_at = ?, revoked_by_user_id = ? WHERE token_id = ?",
            ).use {
                it.setInstant(1, now)
                it.setObject(2, actor.membership.user.userId)
                it.setObject(3, tokenId)
                check(it.executeUpdate() == 1)
            }
            connection.insertAudit(
                actor.membership.workspaceId,
                actor.membership.user.userId,
                actor.membership.user.displayName,
                "DEVICE_TOKEN_REVOKED",
                "DEVICE",
                tokenId,
                now,
                buildJsonObject {
                    put("requestId", requestId)
                    put("memberId", memberId.toString())
                },
            )
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun <T> validate(block: () -> T): T = try {
        block()
    } catch (error: FieldValidationException) {
        throw ServiceErrors.invalid(error.field, error.reason)
    }

    private fun Connection.findInviteForExchange(prefix: String, rawInvite: String): ExchangeInviteRow? =
        prepareStatement(EXCHANGE_INVITE_QUERY).use { statement ->
            statement.setString(1, prefix)
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val storedHash = result.getBytes("secret_hash")
                    val matches = try {
                        hasher.matches(storedHash, rawInvite)
                    } finally {
                        storedHash.fill(0)
                    }
                    if (matches) return result.toExchangeInvite()
                }
                null
            }
        }

    private data class StoredIdempotency(
        val fingerprint: ByteArray,
        val state: String,
        val status: Int,
        val body: JsonElement?,
    )

    private data class DeviceLock(val revokedAt: Instant?)

    private data class ExchangeInviteRow(
        val inviteId: UUID,
        val memberId: UUID,
        val workspaceId: UUID,
        val workspaceName: String,
        val workspaceRevision: Long,
        val userId: UUID,
        val displayName: String,
        val userStatus: String,
        val role: WorkspaceRole,
        val memberVersion: Long,
        val memberCreatedAt: Instant,
        val memberRevokedAt: Instant?,
        val expiresAt: Instant,
        val usedAt: Instant?,
        val revokedAt: Instant?,
    )

    private fun ResultSet.toExchangeInvite() = ExchangeInviteRow(
        inviteId = getObject("invite_id", UUID::class.java),
        memberId = getObject("member_id", UUID::class.java),
        workspaceId = getObject("workspace_id", UUID::class.java),
        workspaceName = getString("workspace_name"),
        workspaceRevision = getLong("workspace_revision"),
        userId = getObject("user_id", UUID::class.java),
        displayName = getString("display_name"),
        userStatus = getString("user_status"),
        role = WorkspaceRole.valueOf(getString("role")),
        memberVersion = getLong("member_version"),
        memberCreatedAt = getTimestamp("member_created_at").toInstant(),
        memberRevokedAt = instantOrNull("member_revoked_at"),
        expiresAt = getTimestamp("expires_at").toInstant(),
        usedAt = instantOrNull("used_at"),
        revokedAt = instantOrNull("invite_revoked_at"),
    )

    companion object {
        val MIN_INVITE_LIFETIME: Duration = Duration.ofHours(1)
        val DEFAULT_INVITE_LIFETIME: Duration = Duration.ofHours(72)
        val MAX_INVITE_LIFETIME: Duration = Duration.ofDays(30)
        val DEVICE_TOKEN_LIFETIME: Duration = Duration.ofDays(90)
        private val LAST_USED_UPDATE_INTERVAL: Duration = Duration.ofMinutes(15)
        private val IDEMPOTENCY_LIFETIME: Duration = Duration.ofHours(24)
        private const val MAX_ACTIVE_INVITES = 5
        private const val MAX_ACTIVE_TOKENS = 10

        private val AUTHENTICATION_QUERY = """
            SELECT t.token_id, t.token_hash, t.member_id, t.device_name, t.token_prefix,
                   t.created_at AS token_created_at, t.last_used_at, t.expires_at AS token_expires_at,
                   t.revoked_at AS token_revoked_at,
                   m.workspace_id, m.user_id, m.role, m.version AS member_version,
                   m.created_at AS member_created_at, m.revoked_at AS member_revoked_at,
                   u.display_name, u.status AS user_status,
                   w.name AS workspace_name, w.revision AS workspace_revision
            FROM access_tokens t
            JOIN workspace_members m ON m.member_id = t.member_id
            JOIN users u ON u.user_id = m.user_id
            JOIN workspaces w ON w.workspace_id = m.workspace_id
            WHERE t.token_prefix = ?
        """.trimIndent()

        private val EXCHANGE_INVITE_QUERY = """
            SELECT i.invite_id, i.secret_hash, i.member_id, i.expires_at, i.used_at,
                   i.revoked_at AS invite_revoked_at,
                   m.workspace_id, m.user_id, m.role, m.version AS member_version,
                   m.created_at AS member_created_at, m.revoked_at AS member_revoked_at,
                   u.display_name, u.status AS user_status,
                   w.name AS workspace_name, w.revision AS workspace_revision
            FROM invites i
            JOIN workspace_members m ON m.member_id = i.member_id
            JOIN users u ON u.user_id = m.user_id
            JOIN workspaces w ON w.workspace_id = m.workspace_id
            WHERE i.secret_prefix = ?
            FOR UPDATE OF i
        """.trimIndent()

        private val INVITE_LIST_QUERY = """
            SELECT i.invite_id, m.workspace_id, i.member_id, m.user_id, u.display_name, m.role,
                   i.created_by_member_id, i.created_at, i.expires_at, i.used_at, i.revoked_at
            FROM invites i
            JOIN workspace_members m ON m.member_id = i.member_id
            JOIN users u ON u.user_id = m.user_id
            WHERE m.workspace_id = ?
            ORDER BY i.created_at DESC, i.invite_id
        """.trimIndent()
    }
}

private fun Connection.insertUser(displayName: String, now: Instant): UUID =
    prepareStatement(
        "INSERT INTO users (display_name, created_at, updated_at) VALUES (?, ?, ?) RETURNING user_id",
    ).use { statement ->
        statement.setString(1, displayName)
        statement.setInstant(2, now)
        statement.setInstant(3, now)
        statement.executeQuery().use { result -> result.next(); result.getObject(1, UUID::class.java) }
    }

private fun Connection.insertWorkspace(name: String, now: Instant): UUID =
    prepareStatement(
        "INSERT INTO workspaces (name, created_at, updated_at) VALUES (?, ?, ?) RETURNING workspace_id",
    ).use { statement ->
        statement.setString(1, name)
        statement.setInstant(2, now)
        statement.setInstant(3, now)
        statement.executeQuery().use { result -> result.next(); result.getObject(1, UUID::class.java) }
    }

private fun Connection.insertMember(
    workspaceId: UUID,
    userId: UUID,
    role: WorkspaceRole,
    now: Instant,
): UUID = prepareStatement(
    """
    INSERT INTO workspace_members (workspace_id, user_id, role, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?) RETURNING member_id
    """.trimIndent(),
).use { statement ->
    statement.setObject(1, workspaceId)
    statement.setObject(2, userId)
    statement.setString(3, role.name)
    statement.setInstant(4, now)
    statement.setInstant(5, now)
    statement.executeQuery().use { result -> result.next(); result.getObject(1, UUID::class.java) }
}

private fun Connection.insertInvite(
    memberId: UUID,
    createdByMemberId: UUID,
    secretHash: ByteArray,
    secretPrefix: String,
    createdAt: Instant,
    expiresAt: Instant,
): UUID = prepareStatement(
    """
    INSERT INTO invites (
        member_id, secret_hash, secret_prefix, created_by_member_id, created_at, expires_at
    ) VALUES (?, ?, ?, ?, ?, ?) RETURNING invite_id
    """.trimIndent(),
).use { statement ->
    statement.setObject(1, memberId)
    statement.setBytes(2, secretHash)
    statement.setString(3, secretPrefix)
    statement.setObject(4, createdByMemberId)
    statement.setInstant(5, createdAt)
    statement.setInstant(6, expiresAt)
    statement.executeQuery().use { result -> result.next(); result.getObject(1, UUID::class.java) }
}

private fun Connection.insertAccessToken(
    memberId: UUID,
    tokenHash: ByteArray,
    tokenPrefix: String,
    deviceName: String,
    createdAt: Instant,
    expiresAt: Instant,
): UUID = prepareStatement(
    """
    INSERT INTO access_tokens (
        member_id, token_hash, token_prefix, device_name, created_at, expires_at
    ) VALUES (?, ?, ?, ?, ?, ?) RETURNING token_id
    """.trimIndent(),
).use { statement ->
    statement.setObject(1, memberId)
    statement.setBytes(2, tokenHash)
    statement.setString(3, tokenPrefix)
    statement.setString(4, deviceName)
    statement.setInstant(5, createdAt)
    statement.setInstant(6, expiresAt)
    statement.executeQuery().use { result -> result.next(); result.getObject(1, UUID::class.java) }
}

private fun Connection.insertAudit(
    workspaceId: UUID,
    actorUserId: UUID?,
    actorDisplayName: String?,
    action: String,
    targetType: String,
    targetId: UUID?,
    timestamp: Instant,
    metadata: JsonObject,
) {
    prepareStatement(
        """
        INSERT INTO audit_events (
            workspace_id, actor_user_id, actor_display_name, action, target_type, target_id, timestamp, metadata
        ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workspaceId)
        statement.setObject(2, actorUserId)
        statement.setString(3, actorDisplayName)
        statement.setString(4, action)
        statement.setString(5, targetType)
        statement.setObject(6, targetId)
        statement.setInstant(7, timestamp)
        statement.setString(8, metadata.toString())
        statement.executeUpdate()
    }
}

private fun Connection.countActiveTokens(memberId: UUID, now: Instant): Int =
    prepareStatement(
        "SELECT count(*) FROM access_tokens WHERE member_id = ? AND revoked_at IS NULL AND expires_at > ?",
    ).use { statement ->
        statement.setObject(1, memberId)
        statement.setInstant(2, now)
        statement.executeQuery().use { result -> result.next(); result.getInt(1) }
    }

private fun Connection.cleanupExpiredIdempotency(now: Instant) {
    prepareStatement(
        """
        DELETE FROM idempotency_records WHERE ctid IN (
            SELECT ctid FROM idempotency_records WHERE expires_at <= ? LIMIT 100
        )
        """.trimIndent(),
    ).use {
        it.setInstant(1, now)
        it.executeUpdate()
    }
}

private fun Connection.revalidateMutationAuthority(
    tokenId: UUID,
    now: Instant,
    requiredCapability: WorkspaceCapability,
) {
    val state = prepareStatement(
        """
        SELECT t.expires_at, t.revoked_at AS token_revoked_at,
               m.role, m.revoked_at AS member_revoked_at, u.status AS user_status
        FROM access_tokens t
        JOIN workspace_members m ON m.member_id = t.member_id
        JOIN users u ON u.user_id = m.user_id
        WHERE t.token_id = ?
        FOR SHARE OF t, m, u
        """.trimIndent(),
    ).use {
        it.setObject(1, tokenId)
        it.executeQuery().use { result ->
            if (!result.next()) null else MutationAuthority(
                expiresAt = result.getTimestamp("expires_at").toInstant(),
                tokenRevokedAt = result.instantOrNull("token_revoked_at"),
                role = WorkspaceRole.valueOf(result.getString("role")),
                memberRevokedAt = result.instantOrNull("member_revoked_at"),
                userStatus = result.getString("user_status"),
            )
        }
    } ?: throw ServiceErrors.unauthenticated()
    if (state.tokenRevokedAt != null) throw ServiceErrors.tokenRevoked()
    if (!state.expiresAt.isAfter(now)) throw ServiceErrors.tokenExpired()
    if (state.memberRevokedAt != null || state.userStatus != "ACTIVE") {
        throw ServiceErrors.forbidden("The Workspace membership is no longer active.")
    }
    if (!state.role.permits(requiredCapability)) {
        throw ServiceErrors.forbidden()
    }
}

private data class MutationAuthority(
    val expiresAt: Instant,
    val tokenRevokedAt: Instant?,
    val role: WorkspaceRole,
    val memberRevokedAt: Instant?,
    val userStatus: String,
)

private fun Connection.lockWorkspaceAuthorization(workspaceId: UUID) {
    prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
        it.setString(1, workspaceId.toString())
        it.executeQuery().close()
    }
}

private fun Connection.requireAnotherAdmin(workspaceId: UUID, excludedMemberId: UUID) {
    val count = prepareStatement(
        """
        SELECT count(*) FROM workspace_members
        WHERE workspace_id = ? AND role = 'ADMIN' AND revoked_at IS NULL AND member_id <> ?
        """.trimIndent(),
    ).use {
        it.setObject(1, workspaceId)
        it.setObject(2, excludedMemberId)
        it.executeQuery().use { result -> result.next(); result.getInt(1) }
    }
    if (count == 0) throw ServiceErrors.lastAdmin()
}

private fun Connection.findMemberForUpdate(workspaceId: UUID, memberId: UUID): MemberRecord? =
    prepareStatement(
        """
        SELECT m.member_id, m.workspace_id, m.user_id, u.display_name, m.role, m.version,
               m.created_at, m.updated_at, m.revoked_at
        FROM workspace_members m JOIN users u ON u.user_id = m.user_id
        WHERE m.workspace_id = ? AND m.member_id = ?
        FOR UPDATE OF m, u
        """.trimIndent(),
    ).use {
        it.setObject(1, workspaceId)
        it.setObject(2, memberId)
        it.executeQuery().use { result -> if (result.next()) result.toMember() else null }
    }

private fun ResultSet.toPrincipal(): AuthenticationPrincipal {
    val memberId = getObject("member_id", UUID::class.java)
    val membership = WorkspaceMembership(
        memberId = memberId,
        workspaceId = getObject("workspace_id", UUID::class.java),
        workspaceName = getString("workspace_name"),
        workspaceRevision = getLong("workspace_revision"),
        user = UserIdentity(getObject("user_id", UUID::class.java), getString("display_name")),
        role = WorkspaceRole.valueOf(getString("role")),
        version = getLong("member_version"),
        createdAt = getTimestamp("member_created_at").toInstant(),
        revokedAt = instantOrNull("member_revoked_at"),
    )
    val device = DeviceTokenMetadata(
        tokenId = getObject("token_id", UUID::class.java),
        memberId = memberId,
        deviceName = getString("device_name"),
        tokenPrefix = getString("token_prefix"),
        createdAt = getTimestamp("token_created_at").toInstant(),
        lastUsedAt = instantOrNull("last_used_at"),
        expiresAt = getTimestamp("token_expires_at").toInstant(),
        revokedAt = instantOrNull("token_revoked_at"),
    )
    return AuthenticationPrincipal(device.tokenId, membership, device)
}

private fun ResultSet.toMember() = MemberRecord(
    memberId = getObject("member_id", UUID::class.java),
    workspaceId = getObject("workspace_id", UUID::class.java),
    userId = getObject("user_id", UUID::class.java),
    displayName = getString("display_name"),
    role = WorkspaceRole.valueOf(getString("role")),
    version = getLong("version"),
    createdAt = getTimestamp("created_at").toInstant(),
    updatedAt = getTimestamp("updated_at").toInstant(),
    revokedAt = instantOrNull("revoked_at"),
)

private fun ResultSet.toDevice() = DeviceTokenMetadata(
    tokenId = getObject("token_id", UUID::class.java),
    memberId = getObject("member_id", UUID::class.java),
    deviceName = getString("device_name"),
    tokenPrefix = getString("token_prefix"),
    createdAt = getTimestamp("created_at").toInstant(),
    lastUsedAt = instantOrNull("last_used_at"),
    expiresAt = getTimestamp("expires_at").toInstant(),
    revokedAt = instantOrNull("revoked_at"),
)

private fun ResultSet.toInviteMetadata(now: Instant): InviteMetadata {
    val expiresAt = getTimestamp("expires_at").toInstant()
    val usedAt = instantOrNull("used_at")
    val revokedAt = instantOrNull("revoked_at")
    val status = when {
        revokedAt != null -> InviteStatus.REVOKED
        usedAt != null -> InviteStatus.USED
        !expiresAt.isAfter(now) -> InviteStatus.EXPIRED
        else -> InviteStatus.ACTIVE
    }
    return InviteMetadata(
        inviteId = getObject("invite_id", UUID::class.java),
        workspaceId = getObject("workspace_id", UUID::class.java),
        memberId = getObject("member_id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        displayName = getString("display_name"),
        role = WorkspaceRole.valueOf(getString("role")),
        createdByMemberId = getObject("created_by_member_id", UUID::class.java),
        createdAt = getTimestamp("created_at").toInstant(),
        expiresAt = expiresAt,
        usedAt = usedAt,
        revokedAt = revokedAt,
        status = status,
    )
}

private fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()
private fun ResultSet.instantOrNull(index: Int): Instant? = getTimestamp(index)?.toInstant()
private fun PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC))
}
