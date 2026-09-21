package com.mesta.asset.controlpanel.judgment

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JdkHttpTransport(
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : JudgmentTransport {
    override fun post(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
    ): TransportResponse {
        val builder =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return TransportResponse(response.statusCode(), response.body())
    }
}
