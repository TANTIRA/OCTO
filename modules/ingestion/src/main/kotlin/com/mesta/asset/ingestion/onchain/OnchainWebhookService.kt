package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.mesta.asset.ingestion.onchain.helius.HeliusTransferNormalizer
import java.util.UUID

/**
 * Webhook delivery path: an array of parsed transactions arrives pushed rather than polled.
 *
 * Each transaction is normalized only for the watched addresses that actually appear in its
 * `accountKeys`, then written through the same staging path as the poller. External ids are
 * deterministic (`solana:<sig>:<account>:<leg>`), so a delivery that overlaps a poll — or a
 * Helius retry of the same delivery — dedupes on `(source_system, external_id)` instead of
 * double-writing facts.
 *
 * The vendor shape stops here: callers pass raw JSON, everything downstream is normalized.
 */
class OnchainWebhookService(
    private val store: OnchainStagingStore,
    private val normalizer: HeliusTransferNormalizer = HeliusTransferNormalizer(),
) {
    /**
     * Ingest one delivery body. [payload] is the webhook's array of parsed transactions;
     * non-array payloads ingest nothing. Returns the number of new staging rows written.
     */
    fun ingest(
        payload: JsonNode,
        ingestionRunId: UUID,
        correlationId: UUID,
        chain: String = CHAIN_SOLANA,
        actor: String = "helius-webhook",
    ): Int {
        if (!payload.isArray || payload.isEmpty) return 0
        val watched = store.activeWatchedAddresses(chain).mapTo(hashSetOf()) { it.address }
        if (watched.isEmpty()) return 0

        val transfers =
            payload.flatMap { tx ->
                watchedAccounts(tx, watched).flatMap { normalizer.normalize(tx, it) }
            }
        return store.insertTransfers(transfers, ingestionRunId, correlationId, actor)
    }

    /**
     * Watched addresses named by the transaction's `accountKeys`. Entries are plain strings
     * in the json encoding and `{"pubkey": ...}` objects in jsonParsed — accept both.
     */
    private fun watchedAccounts(
        tx: JsonNode,
        watched: Set<String>,
    ): List<String> =
        tx
            .path("transaction")
            .path("message")
            .path("accountKeys")
            .mapNotNull { key -> if (key.isTextual) key.asText() else key.path("pubkey").asText(null) }
            .filter(watched::contains)
}
