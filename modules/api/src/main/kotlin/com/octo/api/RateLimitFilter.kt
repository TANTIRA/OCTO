package com.octo.api

import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.persistence.TenantSettingKeys
import com.octo.api.access.persistence.TenantSettings
import com.octo.persistence.TenantScope
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Noisy-neighbor guard (tenancy megaplan slice D): a fixed-window per-tenant quota on authenticated
 * API traffic, counted in Redis (`INCR octo:rl:…:{minute}` + expiry), so one loud tenant cannot
 * consume the shared HikariCP/CPU pool at everyone else's expense.
 *
 * Identity is derived, never trusted: the JWT subject resolves memberships through the same
 * [TenantDirectory] the authorization layer uses. A caller in exactly one tenant counts against that
 * tenant's quota; a multi-tenant caller picks the boundary explicitly with `X-Tenant-Id`, and a
 * header naming a tenant they do not belong to is ignored (falls back to a per-user quota) — a
 * client can never point the limiter at somebody else's counter.
 *
 * Limits: `tenant_setting.rate_limit_per_minute` (V31) per tenant, else `octo.rate-limit
 * .default-per-minute`. Settings reads are cached in-process for 30s so the limiter adds no
 * per-request database round trip beyond the membership lookup the rest of the stack already pays.
 *
 * Failure posture: Redis down → warn once per burst and admit the request. The quota store failing
 * must not become an API outage — authentication and RLS already bound the blast radius.
 */
class RateLimitFilter(
    private val redis: StringRedisTemplate,
    private val settings: TenantSettings,
    private val directory: TenantDirectory,
    private val defaultLimit: Int,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    // tenant → (limit, fetched at). 30s TTL: a settings write takes effect within half a minute.
    private val limitCache = ConcurrentHashMap<UUID, Pair<Int, Instant>>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val jwt = SecurityContextHolder.getContext().authentication?.principal as? Jwt
        if (jwt == null) {
            // Public surface stays free except the one anonymous business write — a lead-form
            // post has no tenant to charge, so it counts against a per-IP window instead. The
            // Traefik edge limiter (deploy/README) is still the primary control; this is the
            // in-app backstop for when the edge is not tuned. Probes (actuator) stay unquota'd.
            if (request.method == "POST" && request.requestURI == CONTACT_PATH) {
                try {
                    val key = "octo:rl:ip:${clientIp(request)}:${minute()}"
                    val count = redis.opsForValue().increment(key) ?: 0L
                    if (count == 1L) {
                        redis.expire(key, Duration.ofSeconds(KEY_TTL_SECONDS))
                    }
                    if (count > CONTACT_IP_LIMIT_PER_MINUTE) {
                        reject(response)
                        return
                    }
                } catch (e: DataAccessException) {
                    log.warn("rate-limit store unavailable — admitting request without quota", e)
                }
            }
            chain.doFilter(request, response)
            return
        }

        val userId = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()
        val tenants = userId?.let { safeTenants(it) } ?: emptyList()
        val tenant = selectTenant(request, tenants)
        val key =
            if (tenant != null) {
                "octo:rl:t:${tenant.tenantId}:${minute()}"
            } else {
                "octo:rl:u:${jwt.subject!!}:${minute()}"
            }
        val limit = tenant?.let { limitFor(it.tenantId) } ?: defaultLimit

        try {
            val count = redis.opsForValue().increment(key) ?: 0L
            if (count == 1L) {
                redis.expire(key, Duration.ofSeconds(KEY_TTL_SECONDS))
            }
            if (count > limit) {
                reject(response)
                return
            }
        } catch (e: DataAccessException) {
            log.warn("rate-limit store unavailable — admitting request without quota", e)
        }
        chain.doFilter(request, response)
    }

    /**
     * One membership → that tenant's counter. Several → the `X-Tenant-Id` header when it names a
     * tenant the caller actually belongs to; anything else (absent, malformed, foreign) falls back
     * to the per-user counter, so a multi-tenant caller cannot fan out across tenant quotas.
     */
    private fun selectTenant(
        request: HttpServletRequest,
        tenants: List<TenantAccess>,
    ): TenantAccess? {
        if (tenants.isEmpty()) return null
        if (tenants.size == 1) return tenants.single()
        val requested = request.getHeader(TENANT_HEADER)?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        return tenants.firstOrNull { it.tenantId == requested }
    }

    private fun safeTenants(userId: UUID): List<TenantAccess> =
        runCatching { directory.tenantsOf(userId) }.getOrElse {
            log.warn("membership lookup failed for rate limiting — falling back to per-user quota", it)
            emptyList()
        }

    private fun limitFor(tenantId: UUID): Int {
        val cached = limitCache[tenantId]
        if (cached != null && cached.second.isAfter(Instant.now())) return cached.first
        val limit =
            runCatching {
                settings
                    .get(tenantId, TenantSettingKeys.RATE_LIMIT_PER_MINUTE, TenantScope.All)
                    ?.trim()
                    ?.trim('"')
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }.getOrNull() ?: defaultLimit
        limitCache[tenantId] = limit to Instant.now().plusSeconds(LIMIT_CACHE_TTL_SECONDS)
        return limit
    }

    private fun reject(response: HttpServletResponse) {
        response.status = 429
        response.contentType = "application/json"
        response.setHeader("Retry-After", (SECONDS_PER_MINUTE - Instant.now().epochSecond % SECONDS_PER_MINUTE).toString())
        response.writer.write("""{"error":"rate_limit_exceeded"}""")
    }

    private fun minute(): Long = Instant.now().epochSecond / SECONDS_PER_MINUTE

    /** XFF leftmost — Traefik fronts the api, so `remoteAddr` alone would quota the proxy. */
    private fun clientIp(request: HttpServletRequest): String =
        request
            .getHeader("X-Forwarded-For")
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr

    companion object {
        const val TENANT_HEADER = "X-Tenant-Id"
        private const val CONTACT_PATH = "/api/v1/contact"
        private const val CONTACT_IP_LIMIT_PER_MINUTE = 10
        private const val SECONDS_PER_MINUTE = 60L
        private const val KEY_TTL_SECONDS = 90L
        private const val LIMIT_CACHE_TTL_SECONDS = 30L
    }
}
