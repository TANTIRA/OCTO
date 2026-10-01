package com.octo.api.contact

import com.octo.api.clientIp
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/v1/contact` — the public lead-capture write behind the landing-page form (#315).
 *
 * This is the API's only anonymous business endpoint, so the contract is deliberately narrow:
 * insert-only into `octo.contact_lead` (V39, pre-tenant — no tenant_id, no RLS), 202 always on
 * accepted input, and no query surface back. Abuse posture: request-shape validation here, an
 * in-app per-IP window in `RateLimitFilter`, and Traefik edge limiting as the primary control
 * (deploy/README). The hidden `website` field is a honeypot — a real browser never renders a
 * non-empty value, so a filled one answers 202 without storing (bot traffic must not learn it
 * was filtered, or it adapts).
 */
@RestController
class ContactController(
    private val leads: ContactStore,
) {
    @PostMapping("/api/v1/contact")
    fun submit(
        @Valid @RequestBody body: ContactBody,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!body.website.isNullOrBlank()) return ResponseEntity.accepted().build()
        leads.record(body.toLead(clientIp(request)))
        return ResponseEntity.accepted().build()
    }

    /**
     * One captured submission. `sourceIp` is the Traefik-appended client address (XFF
     * rightmost, the rate limiter's [clientIp]) — stored for abuse analysis, never echoed back.
     */
    data class ContactLead(
        val email: String,
        val firstName: String,
        val lastName: String,
        val firm: String,
        val role: String,
        val aumBand: String,
        val phone: String?,
        val message: String?,
        val sourceIp: String?,
    )

    /** `website` is the honeypot: absent from the rendered form's reachable controls. */
    data class ContactBody(
        @field:Email @field:NotBlank @field:Size(max = 320) val email: String,
        @field:NotBlank @field:Size(max = 120) val firstName: String,
        @field:NotBlank @field:Size(max = 120) val lastName: String,
        @field:NotBlank @field:Size(max = 200) val firm: String,
        @field:NotBlank @field:Size(max = 120) val role: String,
        @field:NotBlank @field:Size(max = 60) val aumBand: String,
        @field:Size(max = 60) val phone: String? = null,
        @field:Size(max = 2000) val message: String? = null,
        val website: String? = null,
    ) {
        fun toLead(sourceIp: String) =
            ContactLead(
                email.trim(),
                firstName.trim(),
                lastName.trim(),
                firm.trim(),
                role.trim(),
                aumBand.trim(),
                phone?.trim()?.takeIf { it.isNotEmpty() },
                message?.trim()?.takeIf { it.isNotEmpty() },
                sourceIp,
            )
    }
}

fun interface ContactStore {
    fun record(lead: ContactController.ContactLead)
}
