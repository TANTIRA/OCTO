package com.mesta.asset.controlpanel.judgment

class ConfidentialStateRejectedException(val classification: DataClassification) :
    IllegalArgumentException("$classification state may not be sent to a public decision endpoint")

class JudgmentRequestException(
    val statusCode: Int,
    val responseBody: String,
) : RuntimeException("decision request failed with status $statusCode")

data class TransportResponse(val statusCode: Int, val body: String)

fun interface JudgmentTransport {
    fun post(endpoint: String, headers: Map<String, String>, body: String): TransportResponse
}

interface JudgmentClient {
    fun decide(state: ClassifiedState, questions: Map<String, JudgmentQuestion>): JudgmentResult
}
