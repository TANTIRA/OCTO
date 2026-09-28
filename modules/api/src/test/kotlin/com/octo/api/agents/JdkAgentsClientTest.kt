package com.octo.api.agents

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * `JdkAgentsClient` over a loopback `HttpServer`: the shared bearer, workflow path and JSON
 * body are the wire contract the sidecar authenticates on, a non-2xx surfaces as
 * `AgentsCallException`, and an unreachable sidecar is `AgentsUnavailableException` — the
 * distinction the edge turns into 502 vs 503.
 */
class JdkAgentsClientTest {
    @Test
    fun `the run call posts the workflow path with the shared bearer and decodes the answer`() {
        var seenPath: String? = null
        var seenAuth: String? = null
        var seenBody: String? = null
        withServer({ exchange ->
            seenPath = exchange.requestURI.path
            seenAuth = exchange.requestHeaders.getFirst("Authorization")
            seenBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            exchange.respond(200, """{"verdict":{"proceed":true}}""")
        }) { baseUrl ->
            val result =
                JdkAgentsClient(baseUrl, "shared-secret")
                    .run("screening-dd", mapOf("prospect_id" to "p-1"))
            assertThat(result).isEqualTo(mapOf("verdict" to mapOf("proceed" to true)))
            assertThat(seenPath).isEqualTo("/v1/workflows/screening-dd")
            assertThat(seenAuth).isEqualTo("Bearer shared-secret")
            assertThat(seenBody).isEqualTo("""{"prospect_id":"p-1"}""")
        }
    }

    @Test
    fun `a non-2xx answer raises AgentsCallException carrying the status`() {
        withServer({ exchange -> exchange.respond(503, "workflow disabled") }) { baseUrl ->
            val thrown =
                assertThrows<AgentsCallException> {
                    JdkAgentsClient(baseUrl, "t").run("screening-dd", emptyMap())
                }
            assertThat(thrown.statusCode).isEqualTo(503)
            assertThat(thrown.message).contains("workflow disabled")
        }
    }

    @Test
    fun `an unreachable sidecar raises AgentsUnavailableException`() {
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = dead.address.port
        dead.stop(0)
        assertThrows<AgentsUnavailableException> {
            JdkAgentsClient("http://127.0.0.1:$port", "t").run("screening-dd", emptyMap())
        }
    }

    @Test
    fun `a blank base url is refused`() {
        assertThrows<IllegalArgumentException> { JdkAgentsClient("  ", "t") }
    }

    /** Runs [block] against a live loopback server and always stops it. */
    private fun withServer(
        handler: (HttpExchange) -> Unit,
        block: (String) -> Unit,
    ) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        http.start()
        try {
            block("http://127.0.0.1:${http.address.port}")
        } finally {
            http.stop(0)
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
