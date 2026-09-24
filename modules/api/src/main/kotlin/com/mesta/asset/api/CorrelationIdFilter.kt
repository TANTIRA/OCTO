package com.mesta.asset.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * One correlation id per request: taken from a valid `X-Correlation-Id` header, otherwise minted here. It goes
 * into the MDC, so every log line of the request carries it, and back to the caller in the response header. Every
 * append-only table requires a `correlation_id` (data-security-governance.md), and this is where the api gets one.
 */
@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val id = request.getHeader(HEADER)?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() } ?: UUID.randomUUID()
        response.setHeader(HEADER, id.toString())
        MDC.put(MDC_KEY, id.toString())
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlation_id"

        /** The current request's id, for callers that write a `correlation_id` column. */
        fun current(): UUID? = MDC.get(MDC_KEY)?.let(UUID::fromString)
    }
}
