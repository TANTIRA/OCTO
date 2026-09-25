package com.mesta.asset.api.asset

import com.mesta.asset.api.access.TenantDirectory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/v1/assets/{id}` (Marquee asset service, #6 slice 5): one asset with its identifiers, region and
 * tags. Tenant-scoped through [TenantDirectory]: any role in the asset's tenant may read it, and an asset
 * of another tenant, like an unknown id, is 404, so ids leak nothing (default deny, data-security-governance.md).
 */
@RestController
class AssetController(
    private val assets: AssetStore,
    private val tenants: TenantDirectory,
) {
    @GetMapping("/api/v1/assets/{id}")
    fun asset(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<AssetResponse> {
        val record = assets.load(id) ?: return ResponseEntity.notFound().build()
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return ResponseEntity.notFound().build()
        if (tenants.tenantsOf(userId).none { it.tenantId == record.asset.tenantId }) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            AssetResponse(
                id = record.asset.id,
                tenantId = record.asset.tenantId,
                type = record.asset.type.wireValue,
                assetClass = record.asset.assetClass,
                name = record.asset.displayName,
                identifiers = record.identifiers.map { IdentifierView(it.scheme, it.value) },
                region = record.asset.region,
                tags = record.asset.tags,
                supersededBy = record.supersededBy,
                recordedAt = record.recordedAt,
            ),
        )
    }

    data class IdentifierView(
        val scheme: String,
        val value: String,
    )

    data class AssetResponse(
        val id: UUID,
        val tenantId: UUID,
        val type: String,
        val assetClass: String,
        val name: String,
        val identifiers: List<IdentifierView>,
        val region: String?,
        val tags: List<String>,
        val supersededBy: UUID?,
        val recordedAt: Instant,
    )
}
