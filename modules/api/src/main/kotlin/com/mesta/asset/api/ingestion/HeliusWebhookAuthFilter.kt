package com.mesta.asset.api.ingestion

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Shared-secret gate for the Helius webhook — the one request type the JWT resource-server
 * chain can never authenticate, because the caller is a vendor, not a user.
 *
 * The operator configures Helius to send `Authorization: <HELIUS_WEBHOOK_SECRET>` at
 * registration; the filter compares SHA-256 digests so the compare is constant-time
 * independent of input length. The secret lives only in the environment — never logged.
 *
 * Fail-closed on both axes: a missing or wrong header is a 401 before the chain is reached,
 * and with no secret configured the endpoint rejects every request (a webhook that cannot be
 * verified is not a webhook).
 */
class HeliusWebhookAuthFilter(
    private val secret: String?,
) : OncePerRequestFilter() {
    private val expectedDigest: ByteArray? = secret?.takeIf(String::isNotBlank)?.let(::sha256)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val provided = request.getHeader("Authorization")
        if (expectedDigest == null || provided == null || !MessageDigest.isEqual(sha256(provided), expectedDigest)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "helius-webhook",
                null,
                listOf(SimpleGrantedAuthority("ROLE_HELIUS_WEBHOOK")),
            )
        chain.doFilter(request, response)
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
