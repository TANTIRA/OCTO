package com.octo.api.access

import com.octo.api.access.persistence.AccessAdministration
import com.octo.api.access.persistence.AccessProvenance
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * Tenant provisioning — the API edge the store layer never had (tenancy megaplan slice B).
 *
 * Creating a tenant is a platform action, gated by [PlatformAdmin] (`OCTO_PLATFORM_ADMINS`), not
 * by any tenant role: an organization cannot mint another organization. Membership events on an
 * existing tenant admit either a platform admin or that tenant's own ADMIN — the same person who
 * would run the SQL today, minus the database access.
 *
 * Everything here replays through the membership state machine, so its rules — one grant at a
 * time, ordered events, nobody granting their own access — hold at the edge exactly as they do
 * in `JdbcAccessStore.append`.
 */
@RestController
class AdminTenantsController(
    private val access: AccessAdministration,
    private val directory: TenantDirectory,
    private val platform: PlatformAdmin,
) {
    @PostMapping("/api/v1/admin/tenants")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody body: CreateTenant,
    ): ResponseEntity<TenantView> {
        requirePlatformAdmin(jwt)
        val tenant = Tenant(id = UUID.randomUUID(), slug = body.slug.trim(), displayName = body.displayName.trim())
        val state =
            try {
                access.provisionTenant(
                    tenant = tenant,
                    adminUserId = body.firstAdminUserId,
                    grantor = jwt.subject,
                    registeredAt = Instant.now(),
                    provenance = AccessProvenance(SOURCE, UUID.randomUUID()),
                )
            } catch (e: SQLException) {
                throw translate(e)
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            TenantView(
                tenant.id,
                tenant.slug,
                tenant.displayName,
                MemberView(body.firstAdminUserId, state.role!!.wireValue, state.status.name.lowercase()),
            ),
        )
    }

    @PostMapping("/api/v1/admin/tenants/{tenantId}/members/{userId}")
    fun event(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
        @RequestBody body: MemberEvent,
    ): MemberView {
        requireMemberAdmin(jwt, tenantId)
        val now = Instant.now()
        val provenance = AccessProvenance(SOURCE, UUID.randomUUID())
        val state =
            try {
                when (body.type) {
                    "granted" -> {
                        val role = roleOf(body.role)
                        if (access.load(tenantId, userId) == null) {
                            access.registerMember(tenantId, userId, now, provenance)
                        }
                        access.append(tenantId, userId, MembershipEvent.Granted(jwt.subject, now, role), provenance)
                    }
                    "role-changed" ->
                        access.append(tenantId, userId, MembershipEvent.RoleChanged(jwt.subject, now, roleOf(body.role)), provenance)
                    "revoked" ->
                        access.append(
                            tenantId,
                            userId,
                            MembershipEvent.Revoked(
                                jwt.subject,
                                now,
                                body.rationale?.takeIf(String::isNotBlank)
                                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a revocation needs a rationale"),
                            ),
                            provenance,
                        )
                    else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown member event '${body.type}'")
                }
            } catch (e: NoSuchElementException) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
            } catch (e: SQLException) {
                throw translate(e)
            }
        return MemberView(userId, state.role?.wireValue, state.status.name.lowercase())
    }

    private fun requirePlatformAdmin(jwt: Jwt) {
        if (!platform.isAdmin(jwt.subject)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "tenant provisioning needs a platform admin")
        }
    }

    private fun requireMemberAdmin(
        jwt: Jwt,
        tenantId: UUID,
    ) {
        if (platform.isAdmin(jwt.subject)) return
        val caller = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
        val tenantAdmin =
            caller
                ?.let(directory::tenantsOf)
                ?.any { it.tenantId == tenantId && it.role == TenantRole.ADMIN }
                ?: false
        if (!tenantAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "member administration needs a platform or tenant admin")
        }
    }

    private fun roleOf(role: String?): TenantRole =
        role?.let { runCatching { TenantRole.fromWireValue(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "event needs a valid role")

    private fun translate(e: SQLException): ResponseStatusException =
        when (e.sqlState) {
            "23505" -> ResponseStatusException(HttpStatus.CONFLICT, "a row with that identity already exists", e)
            "23514", "23502", "23503" -> ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            else -> ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }

    data class CreateTenant(
        val slug: String,
        val displayName: String,
        val firstAdminUserId: UUID,
    )

    data class MemberEvent(
        val type: String,
        val role: String? = null,
        val rationale: String? = null,
    )

    data class MemberView(
        val userId: UUID,
        val role: String?,
        val status: String,
    )

    data class TenantView(
        val tenantId: UUID,
        val slug: String,
        val displayName: String,
        val firstAdmin: MemberView,
    )

    private companion object {
        const val SOURCE = "admin-api"
    }
}
