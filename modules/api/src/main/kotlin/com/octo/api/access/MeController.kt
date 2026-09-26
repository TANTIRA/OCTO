package com.mesta.asset.api.access

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `GET /api/v1/me/access` — the caller's own tenant access: which organizations the authenticated
 * user may act in and in which role. This is what the web app and external consumers read to learn
 * their accessibility; tenant-scoped endpoints authorize against the same [TenantDirectory].
 *
 * The subject is the Supabase Auth user id. A bearer token whose subject is not a user id simply
 * resolves to no tenants — default is deny (data-security-governance.md).
 */
@RestController
class MeController(
    private val tenants: TenantDirectory,
) {
    @GetMapping("/api/v1/me/access")
    fun access(
        @AuthenticationPrincipal jwt: Jwt,
    ): AccessResponse {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
        return AccessResponse(
            userId = jwt.subject,
            tenants =
                userId
                    ?.let(tenants::tenantsOf)
                    .orEmpty()
                    .map { TenantView(it.tenantId, it.slug, it.role.wireValue) },
        )
    }

    data class TenantView(
        val tenantId: UUID,
        val slug: String,
        val role: String,
    )

    data class AccessResponse(
        val userId: String,
        val tenants: List<TenantView>,
    )
}
