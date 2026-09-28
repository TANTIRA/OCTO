package com.octo.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Local-testing shim: injects a synthetic JWT so every request authenticates as one fixed
 * subject without a Supabase issuer. Registered only when `AUTH_DEV_BYPASS=true` (see
 * [SecurityConfig]); with the flag unset this filter never enters the chain and the API
 * keeps its fail-closed posture.
 *
 * The subject is `AUTH_DEV_SUBJECT`, defaulting to the all-zeros-plus-one UUID below.
 * Tenant access still derives from `mesta.tenant_member_event`, so a bypassed caller
 * with no membership rows sees the same "no tenants" result an unknown JWT would.
 */
class DevSubjectAuthFilter(
    subject: String?,
) : OncePerRequestFilter() {
    private val token: JwtAuthenticationToken =
        JwtAuthenticationToken(
            Jwt
                .withTokenValue("dev-bypass")
                .header("alg", "none")
                .issuer("dev-bypass")
                .subject(subject?.takeIf(String::isNotBlank) ?: DEFAULT_SUBJECT)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build(),
            // The (Jwt, authorities) constructor is the trusted one: the single-arg
            // variant builds an *unauthenticated* token and the chain would 403.
            emptyList<GrantedAuthority>(),
        )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        SecurityContextHolder.getContext().authentication = token
        chain.doFilter(request, response)
    }

    private companion object {
        const val DEFAULT_SUBJECT = "00000000-0000-0000-0000-000000000001"
    }
}
