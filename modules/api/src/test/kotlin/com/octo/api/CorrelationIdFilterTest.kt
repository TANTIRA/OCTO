package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

class CorrelationIdFilterTest {
    private val filter = CorrelationIdFilter()

    /** Runs the filter and returns the MDC value the downstream chain saw, plus the response. */
    private fun run(header: String?): Pair<String?, MockHttpServletResponse> {
        val request = MockHttpServletRequest("GET", "/api/funds").apply { header?.let { addHeader(CorrelationIdFilter.HEADER, it) } }
        val response = MockHttpServletResponse()
        var seen: String? = null
        filter.doFilter(
            request,
            response,
            MockFilterChain(
                object : jakarta.servlet.http.HttpServlet() {},
                jakarta.servlet.Filter { _, _, _ ->
                    seen =
                        MDC.get(CorrelationIdFilter.MDC_KEY)
                },
            ),
        )
        return seen to response
    }

    @Test
    fun `a valid header is kept, put in the MDC and echoed`() {
        val id = UUID.randomUUID().toString()
        val (seen, response) = run(id)

        assertThat(seen).isEqualTo(id)
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(id)
    }

    @Test
    fun `a missing or malformed header gets a minted id`() {
        for (header in listOf(null, "not-a-uuid", "")) {
            val (seen, response) = run(header)
            val echoed = response.getHeader(CorrelationIdFilter.HEADER)

            assertThat(echoed).describedAs("header=$header").isNotNull()
            assertThat(UUID.fromString(echoed)).isNotNull()
            assertThat(seen).isEqualTo(echoed)
        }
    }

    @Test
    fun `the MDC is cleared after the request`() {
        run(null)

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull()
        assertThat(CorrelationIdFilter.current()).isNull()
    }
}
