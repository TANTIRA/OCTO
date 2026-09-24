package com.mesta.asset.api.ingestion

import com.fasterxml.jackson.databind.JsonNode
import com.mesta.asset.ingestion.onchain.OnchainWebhookService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Receives Helius webhook deliveries. Auth happens in `HeliusWebhookAuthFilter` on the
 * dedicated security chain; by the time a request reaches this controller it is verified.
 *
 * The body is the webhook's array of parsed transactions (the operator registers a webhook
 * whose payload parses to `getTransaction`-style objects — see the registration runbook).
 * Each delivery gets its own `ingestion_run_id`; a Helius retry shares the same
 * `X-Helius-Webhook-Id`, which becomes the `correlation_id` linking the attempts.
 */
@RestController
class HeliusWebhookController(
    private val webhookService: OnchainWebhookService,
) {
    @PostMapping("/api/v1/ingestion/webhooks/helius")
    fun receive(
        @RequestBody payload: JsonNode,
        @RequestHeader("X-Helius-Webhook-Id", required = false) deliveryId: String?,
    ): ResponseEntity<Map<String, Int>> {
        val inserted =
            webhookService.ingest(
                payload = payload,
                ingestionRunId = UUID.randomUUID(),
                correlationId = deliveryId?.let(::parseUuid) ?: UUID.randomUUID(),
            )
        return ResponseEntity.ok(mapOf("inserted" to inserted))
    }

    private fun parseUuid(value: String): UUID =
        runCatching {
            UUID.fromString(value)
        }.getOrElse { UUID.nameUUIDFromBytes(value.toByteArray()) }
}
