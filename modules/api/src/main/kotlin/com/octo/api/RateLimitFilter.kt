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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Noisy-neighbor guard (tenancy megaplan slice D): a fixed-window per-tenant quota on authenticated
 * API traffic, so one loud tenant cannot consume the shared HikariCP/CPU pool at everyone else's
 * expense. Traffic with no tenant to charge — failed authentication, the anonymous lead form — is
 * bounded per client IP by [ClientIpRateLimitFilter] ahead of authentication.
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
 */
class RateLimitFilter(
    private val counter: QuotaCounter,
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
            chain.doFilter(request, response)
            return
        }

        val userId = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()
        val tenants = userId?.let { safeTenants(it) } ?: emptyList()
        val tenant = selectTenant(request, tenants)
        val key = if (tenant != null) "octo:rl:t:${tenant.tenantId}" else "octo:rl:u:${jwt.subject!!}"
        val limit = tenant?.let { limitFor(it.tenantId) } ?: defaultLimit

        if (counter.increment(key) > limit) {
            rejectTooManyRequests(response)
            return
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

    companion object {
        const val TENANT_HEADER = "X-Tenant-Id"
        private const val LIMIT_CACHE_TTL_SECONDS = 30L
    }
}

/**
 * Per-IP guard for traffic that has no tenant to charge (#321). Runs ahead of authentication in
 * both the JWT chain and the webhook chain, so it sees requests the tenant quota never does:
 *
 * - **Failed authentication** — a 401, or a 403 for a caller who never authenticated. Only
 *   failures are charged, so legitimate traffic from a shared office IP is never counted; once an
 *   IP reaches [AUTH_FAILURES_PER_MINUTE] every request from it answers 429 until the window
 *   rolls, which stops token and webhook-secret guessing floods before they reach the JWKS
 *   decoder or the digest compare.
 * - **The anonymous lead form** (`POST /api/v1/contact`, #315) — every call is charged, at
 *   [CONTACT_PER_MINUTE].
 *
 * The Traefik edge limiter (deploy/README) is defense in depth on top of this, not a precondition.
 */
class ClientIpRateLimitFilter(
    private val counter: QuotaCounter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val ip = clientIp(request)
        val failuresKey = "octo:rl:authfail:$ip"
        if (counter.current(failuresKey) >= AUTH_FAILURES_PER_MINUTE) {
            rejectTooManyRequests(response)
            return
        }
        if (request.method == "POST" && request.requestURI == CONTACT_PATH &&
            counter.increment("octo:rl:ip:$ip") > CONTACT_PER_MINUTE
        ) {
            rejectTooManyRequests(response)
            return
        }

        chain.doFilter(request, response)

        if (response.status == HttpServletResponse.SC_UNAUTHORIZED ||
            (response.status == HttpServletResponse.SC_FORBIDDEN && unauthenticated())
        ) {
            counter.increment(failuresKey)
        }
    }

    // After a failed authentication the chain leaves an empty or anonymous context behind.
    private fun unauthenticated(): Boolean {
        val auth = SecurityContextHolder.getContext().authentication
        return auth == null || auth is AnonymousAuthenticationToken
    }

    /**
     * Rightmost `X-Forwarded-For` entry: the address Traefik — the api's only ingress — appended
     * for the peer it actually saw. Entries left of it are client-supplied, so keying on the
     * leftmost would let a caller rotate the header and never hit a limit. Same source the edge
     * limiter's `ipStrategy.depth: 1` uses.
     */
    private fun clientIp(request: HttpServletRequest): String =
        request
            .getHeader("X-Forwarded-For")
            ?.substringAfterLast(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr

    companion object {
        const val AUTH_FAILURES_PER_MINUTE = 30
        const val CONTACT_PER_MINUTE = 10
        private const val CONTACT_PATH = "/api/v1/contact"
    }
}

/**
 * Fixed one-minute windows for both limiters. Counts live in Redis (`INCR octo:rl:…:{minute}` +
 * expiry) when `REDIS_HOST` is configured, so replicas share one quota. With no Redis configured,
 * or while Redis is failing, counts fall back to an in-process window: quotas stay enforced per
 * instance instead of failing open, and the quota store failing still never becomes an API outage.
 * A Redis failure is logged once and Redis is skipped for [REDIS_RETRY], so a dead store neither
 * floods the log nor adds a timeout to every request.
 */
class QuotaCounter(
    private val redis: StringRedisTemplate?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val local = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var localWindow = 0L

    @Volatile private var redisRetryAt: Instant = Instant.MIN

    /** Counts one hit against [key] in the current window and returns the new total. */
    fun increment(key: String): Long {
        val window = window()
        viaRedis {
            val windowKey = "$key:$window"
            val count = it.opsForValue().increment(windowKey) ?: 0L
            if (count == 1L) it.expire(windowKey, KEY_TTL)
            count
        }?.let { return it }
        return localCounter(key, window).incrementAndGet()
    }

    /** The current window's total for [key], without counting a hit. */
    fun current(key: String): Long {
        val window = window()
        viaRedis { it.opsForValue().get("$key:$window")?.toLongOrNull() ?: 0L }?.let { return it }
        if (window != localWindow) return 0L
        return (local[key] ?: local[OVERFLOW_KEY].takeIf { local.size >= MAX_LOCAL_KEYS })?.get() ?: 0L
    }

    private fun <T : Any> viaRedis(op: (StringRedisTemplate) -> T): T? {
        val template = redis ?: return null
        if (Instant.now().isBefore(redisRetryAt)) return null
        return try {
            op(template)
        } catch (e: RuntimeException) {
            redisRetryAt = Instant.now().plus(REDIS_RETRY)
            log.warn("rate-limit store unavailable — enforcing in-process quotas for {}s", REDIS_RETRY.seconds, e)
            null
        }
    }

    private fun localCounter(
        key: String,
        window: Long,
    ): AtomicLong {
        if (window != localWindow) {
            synchronized(local) {
                if (window != localWindow) {
                    local.clear()
                    localWindow = window
                }
            }
        }
        // ponytail: past the cap, new keys (a botnet's worth of IPs inside one minute) share one
        // overflow counter, so memory stays bounded and the flood 429s together — collateral for
        // any legitimate newcomer that minute. Configure Redis if that matters.
        val bounded = if (local.size >= MAX_LOCAL_KEYS && !local.containsKey(key)) OVERFLOW_KEY else key
        return local.computeIfAbsent(bounded) { AtomicLong() }
    }

    private fun window(): Long = Instant.now().epochSecond / SECONDS_PER_MINUTE

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val MAX_LOCAL_KEYS = 100_000
        const val OVERFLOW_KEY = "octo:rl:overflow"
        val KEY_TTL: Duration = Duration.ofSeconds(90)
        val REDIS_RETRY: Duration = Duration.ofSeconds(30)
    }
}

private fun rejectTooManyRequests(response: HttpServletResponse) {
    val secondsPerMinute = 60L
    response.status = 429
    response.contentType = "application/json"
    response.setHeader("Retry-After", (secondsPerMinute - Instant.now().epochSecond % secondsPerMinute).toString())
    response.writer.write("""{"error":"rate_limit_exceeded"}""")
}
