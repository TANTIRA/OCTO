package com.octo.api.agents

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The sidecar answered with a non-2xx — its body explains (flag off, model registry miss). */
class AgentsCallException(
    val statusCode: Int,
    body: String,
) : RuntimeException("agents call failed with status $statusCode: ${body.take(256)}")

/** The sidecar could not be reached or did not answer in time — the feature, not the api, is down. */
class AgentsUnavailableException(
    cause: Throwable,
) : RuntimeException("agents service unreachable", cause)

/**
 * The platform's only client of the ADR-0005 sidecar: one JSON POST per workflow, shared bearer,
 * dokploy-network address. Synchronous — a deepagent run takes tens of seconds and the timeout is
 * sized for it; anything longer is the async-trigger surface, not this client.
 */
fun interface AgentsClient {
    fun run(
        workflow: String,
        payload: Map<String, Any>,
    ): Map<String, Any>
}

class JdkAgentsClient(
    private val baseUrl: String,
    private val token: String,
    private val timeout: Duration = Duration.ofSeconds(120),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : AgentsClient {
    init {
        require(baseUrl.isNotBlank()) { "agents base url must not be blank" }
    }

    override fun run(
        workflow: String,
        payload: Map<String, Any>,
    ): Map<String, Any> {
        val request =
            HttpRequest
                .newBuilder(URI.create("${baseUrl.trimEnd('/')}/v1/workflows/$workflow"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw AgentsUnavailableException(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AgentsUnavailableException(e)
            }
        if (response.statusCode() !in 200..299) {
            throw AgentsCallException(response.statusCode(), response.body())
        }
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(response.body(), Map::class.java) as Map<String, Any>
    }
}
