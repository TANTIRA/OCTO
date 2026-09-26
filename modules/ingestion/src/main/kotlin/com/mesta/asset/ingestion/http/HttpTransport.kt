package com.mesta.asset.ingestion.http

import java.net.http.HttpRequest

/** Minimal response shape the vendor clients need — keeps tests free of JDK HttpResponse stubs. */
data class TransportResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
)

/**
 * The HTTP seam the vendor clients talk through. Tests inject a fake transport so no call ever
 * leaves the process; production wires `java.net.http.HttpClient::send`.
 */
fun interface HttpTransport {
    fun send(request: HttpRequest): TransportResponse
}
