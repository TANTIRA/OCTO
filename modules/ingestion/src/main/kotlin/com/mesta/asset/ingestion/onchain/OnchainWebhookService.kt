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
 *
 * Deliveries carry no commitment promise, so before anything is normalized the service
 * checks each transaction's signature against [finality] and drops what the chain has not
 * finalized (#167) — staging can only ever hold `commitment='finalized'` rows, and the claim
 * is now observed rather than stamped. The poller stages the dropped transactions when they
 * finalize; deterministic external ids dedupe the overlap.
 */
class OnchainWebhookService(
    private val store: OnchainStagingStore,
    private val finality: FinalityProbe,
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
        val tokenOwners = store.watchedTokenAccounts(chain)

        val finalized = finality.finalizedSignatures(payload.mapNotNull(::signatureOf).toSet())
        val transfers =
            payload
                .filter { signatureOf(it) in finalized }
                .flatMap { tx ->
                    watchedAccounts(tx, watched, tokenOwners).flatMap { normalizer.normalize(tx, it) }
                }
        return store.insertTransfers(transfers, ingestionRunId, correlationId, actor)
    }

    /** The transaction's own signature — `transaction.signatures[0]`; a tx without one cannot be verified and is dropped. */
    private fun signatureOf(tx: JsonNode): String? =
        tx
            .path("transaction")
            .path("signatures")
            .takeIf { it.isArray && !it.isEmpty }
            ?.get(0)
            ?.asText()
            ?.takeIf(String::isNotBlank)

    /**
     * Watched wallets this transaction is relevant to: an `accountKeys` entry either is a
     * watched address itself or is a token account owned by one. Entries are plain strings
     * in the json encoding and `{"pubkey": ...}` objects in jsonParsed — accept both.
     * A wallet with two ATAs in one transaction still normalizes once — the deterministic
     * external ids keep a second normalization from writing anything new.
     */
    private fun watchedAccounts(
        tx: JsonNode,
        watched: Set<String>,
        tokenOwners: Map<String, String>,
    ): List<String> =
        tx
            .path("transaction")
            .path("message")
            .path("accountKeys")
            .mapNotNull { key -> if (key.isTextual) key.asText() else key.path("pubkey").asText(null) }
            .mapNotNull { account -> if (account in watched) account else tokenOwners[account]?.takeIf { it in watched } }
            .distinct()
}
