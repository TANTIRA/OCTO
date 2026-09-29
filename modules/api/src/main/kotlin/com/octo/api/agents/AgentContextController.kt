package com.octo.api.agents

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.access.TenantDirectory
import com.octo.api.access.persistence.TenantSettingKeys
import com.octo.api.access.persistence.TenantSettings
import com.octo.persistence.TenantScope
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `GET /api/v1/agent-context` — the sidecar's read of the tenant's standing context (F11).
 * Returns only the agent-facing keys (currently `agents.warm_context`), not the whole
 * settings map: configuration meant for the admin edge stays admin-eyed. Any role in the
 * tenant may read it — the text shapes the outputs they see anyway; another tenant's id is
 * 404 like every other scoped route.
 */
@RestController
class AgentContextController(
    private val settings: TenantSettings,
    private val tenants: TenantDirectory,
    private val json: ObjectMapper,
) {
    @GetMapping("/api/v1/agent-context")
    fun context(
        @RequestParam tenantId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId =
            runCatching { UUID.fromString(jwt.subject) }.getOrNull()
                ?: return ResponseEntity.notFound().build()
        if (tenants.tenantsOf(userId).none { it.tenantId == tenantId }) {
            return ResponseEntity.notFound().build()
        }
        // Settings store JSON text; the sidecar wants the string, not a quoted literal.
        val warm =
            settings
                .get(tenantId, TenantSettingKeys.AGENT_WARM_CONTEXT, TenantScope.User(userId))
                ?.let { runCatching { json.readTree(it) }.getOrNull() }
                ?.let { if (it.isTextual) it.asText() else it.toString() }
        return ResponseEntity.ok(mapOf("warmContext" to warm))
    }
}
