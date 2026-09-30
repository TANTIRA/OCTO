package com.octo.api.brain

import com.octo.api.access.TenantDirectory
import com.octo.api.agents.AgentsCallException
import com.octo.api.agents.AgentsClient
import com.octo.api.agents.AgentsUnavailableException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `POST /api/v1/company-brain/query` — the F7 natural-language surface over the platform's
 * records: jev pre-gates whether the question is answerable at all, the drafter answers through
 * the read-only tool surface, and a second jev gate decides whether the answer is supported
 * enough to ship. The sidecar's result (`status`, `answer`, `verdict`, `note`) passes through.
 *
 * Read semantics: any role in the tenant may ask, including viewer — the workflow mints nothing.
 * Another tenant's id and an unauthenticated call are 404/403 like every other scoped route.
 * Sidecar down or flag-off answers 503; its own non-503 failure surfaces as 502.
 */
@RestController
class CompanyBrainController(
    private val agents: AgentsClient,
    private val tenants: TenantDirectory,
) {
    @PostMapping("/api/v1/company-brain/query")
    fun query(
        @Valid @RequestBody body: QueryRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId =
            runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()
                ?: return ResponseEntity.notFound().build()
        if (tenants.tenantsOf(userId).none { it.tenantId == body.tenantId }) {
            return ResponseEntity.notFound().build()
        }
        val result =
            try {
                agents.run(
                    "company-brain",
                    mapOf(
                        "tenant_id" to body.tenantId.toString(),
                        "question" to body.question,
                        "run_key" to UUID.randomUUID().toString(),
                    ),
                )
            } catch (e: AgentsUnavailableException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            } catch (e: AgentsCallException) {
                return ResponseEntity
                    .status(
                        if (e.statusCode == 503) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY,
                    ).build()
            }
        return ResponseEntity.ok(result)
    }

    data class QueryRequest(
        val tenantId: UUID,
        @field:NotBlank @field:Size(max = 2000) val question: String,
    )
}
