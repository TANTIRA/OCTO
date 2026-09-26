package com.mesta.asset.ingestion.http

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.HttpRequest

/** Queued fake transport: each send returns the next canned response; requests are recorded. */
class FakeTransport(
    vararg responses: TransportResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    private val queue = ArrayDeque(responses.toList())
    private val mapper = ObjectMapper()

    override fun send(request: HttpRequest): TransportResponse {
        requests += request
        return queue.removeFirst()
    }

    /** Parse the JSON-RPC body out of a recorded POST request. */
    fun bodyOf(index: Int): com.fasterxml.jackson.databind.JsonNode {
        val publisher = requests[index].bodyPublisher().orElseThrow()
        val bytes = java.io.ByteArrayOutputStream()
        publisher.subscribe(
            object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
                override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) = s.request(Long.MAX_VALUE)

                override fun onNext(item: java.nio.ByteBuffer) = bytes.writeBytes(item.array())

                override fun onError(t: Throwable) {}

                override fun onComplete() {}
            },
        )
        return mapper.readTree(bytes.toByteArray())
    }
}

fun okJson(body: String): TransportResponse = TransportResponse(200, emptyMap(), body)

fun statusOf(
    code: Int,
    retryAfterSeconds: Int? = null,
): TransportResponse = TransportResponse(code, retryAfterSeconds?.let { mapOf("Retry-After" to listOf(it.toString())) }.orEmpty(), "")
