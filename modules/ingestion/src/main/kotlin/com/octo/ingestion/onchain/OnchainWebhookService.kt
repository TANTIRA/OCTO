package com.octo.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.octo.ingestion.onchain.helius.HeliusTransferNormalizer
import com.octo.ingestion.onchain.helius.HeliusTransferNormalizer.TransferParse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Webhook delivery path: an array of parsed transactions arrives pushed rather than polled.
 *
 * Each transaction is normalized only for the watched addresses that actually appear in its
 * `accountKeys`, then written through the same staging path as the poller. External ids are
 * deterministic (`solana:<sig>:<account>:<leg>`), so a delivery that overlaps a poll — or a
 * Helius retry of the same delivery — dedupes on `(source_system, external_id)` instead of
 * double-writing facts. A fee that an older balance leg already includes is left there.
 *
 * The vendor shape stops here: callers pass raw JSON, everything downstream is normalized.
 *
 * Deliveries carry no commitment promise, so before anything is normalized the service
 * checks each transaction's signature against [finality] and stages only what the chain has
 * finalized (#167). Helius delivers ~13 s before finalization and retries only three times,
 * one second apart — answering an error for a not-yet-final signature just burns the retries
 * before the transaction can ever succeed (#483). Instead, signatures that are not finalized
 * yet — or finalized but whose canonical copy cannot be fetched — are held in a bounded
 * in-process re-check set, and [recheckDeferred] re-probes them until they stage or expire.
 * The set is in-memory, so a restart inside the finality window loses what it held; the
 * bounded window makes that strictly narrower than the permanent drop it replaced.
 *
 * A finalized signature is not itself trustworthy content (#316): it proves *a* transaction
 * landed, not that the caller's `accountKeys`/balance payload describes it honestly. Every
 * finalized signature is re-fetched through [transactions] and normalized from that canonical
 * copy — the delivered payload is used only to learn which signatures to look up, never as a
 * source of transfer facts.
 */
class OnchainWebhookService(
    private val store: OnchainStagingStore,
    private val finality: FinalityProbe,
    private val transactions: TransactionFetcher,
    private val normalizer: HeliusTransferNormalizer = HeliusTransferNormalizer(),
    private val clock: Clock = Clock.systemUTC(),
    private val maxDeferred: Int = DEFAULT_MAX_DEFERRED,
    private val deferredMaxAge: Duration = DEFAULT_DEFERRED_MAX_AGE,
) {
    private val log = Logger.getLogger(OnchainWebhookService::class.java.name)

    /**
     * Signatures held for re-check, keyed so Helius retries and duplicate deliveries collapse
     * onto one entry. The enqueued timestamp is the first-seen time on our own clock — never
     * the payload's `blockTime` — so expiry cannot be moved by what a sender claims.
     */
    private val deferredLock = Any()
    private val deferred = LinkedHashMap<String, DeferredSignature>()

    private data class DeferredSignature(
        val enqueuedAt: Instant,
        val correlationId: UUID,
    )

    /**
     * What one delivery did. [deferred] counts signatures held for re-check; [rejected]
     * counts signatures that should have been held but were refused because the re-check set
     * was full — the caller turns those into a retryable status.
     */
    data class WebhookIngest(
        val staged: Int,
        val deferred: Int,
        val rejected: Int,
    )

    /** What one re-check pass did: [staged] rows written, [expired] entries aged out, [pending] still held. */
    data class WebhookRecheck(
        val staged: Int,
        val expired: Int,
        val pending: Int,
    )

    /**
     * Ingest one delivery body. [payload] is the webhook's array of parsed transactions;
     * non-array payloads ingest nothing.
     */
    fun ingest(
        payload: JsonNode,
        ingestionRunId: UUID,
        correlationId: UUID,
        chain: String = CHAIN_SOLANA,
        actor: String = "helius-webhook",
    ): WebhookIngest {
        if (!payload.isArray || payload.isEmpty) return WebhookIngest(0, 0, 0)
        val watched = store.activeWatchedAddresses(chain).mapTo(hashSetOf()) { it.address }
        if (watched.isEmpty()) return WebhookIngest(0, 0, 0)

        val signatures = payload.mapNotNull(::signatureOf)
        val unsigned = payload.size() - signatures.size
        if (unsigned > 0) {
            log.warning("dropped $unsigned webhook transactions with no verifiable signature — check the webhook payload encoding")
        }

        val finalized = finality.finalizedSignatures(signatures.toSet())
        var heldNow = 0
        var rejected = 0
        for (sig in signatures) {
            if (sig !in finalized) {
                if (hold(sig, correlationId)) heldNow++ else rejected++
            }
        }
        if (heldNow > 0) {
            log.info("deferred $heldNow webhook transactions pending finality; the re-check pass picks them up")
        }
        if (rejected > 0) {
            log.warning("refused $rejected webhook transactions — the deferred re-check set is full ($maxDeferred)")
        }

        val canonical = finalized.mapNotNull { sig -> transactions.fetch(sig)?.let { sig to it } }.toMap()
        val notFetched = finalized - canonical.keys
        // Finalized but unfetchable is the same retryable state as not-yet-final: the
        // re-check pass re-fetches until it succeeds or the entry expires.
        for (sig in notFetched) {
            if (hold(sig, correlationId)) heldNow++ else rejected++
        }
        if (notFetched.isNotEmpty()) {
            log.warning("could not fetch canonical content for ${notFetched.size} finalized signatures; held for re-check")
        }

        val parses = parseAll(canonical.values, watched, clock.instant())
        val staged = store.insertTransfers(parses.flatMap { it.legs }, ingestionRunId, correlationId, actor)
        return WebhookIngest(staged, heldNow, rejected)
    }

    /**
     * One re-check pass over held signatures: re-probe finality, fetch and stage what
     * finalized, and age out entries older than [deferredMaxAge]. Staging uses each entry's
     * original delivery `correlation_id`, so a staged row still links to the delivery that
     * first carried its signature. Entries that remain unfinalized or unfetchable stay held
     * for the next pass; exceptions propagate so the caller can retry the whole pass.
     */
    fun recheckDeferred(
        chain: String = CHAIN_SOLANA,
        actor: String = "helius-webhook",
    ): WebhookRecheck {
        val now = clock.instant()
        val due: Map<String, UUID>
        val expired: Int
        synchronized(deferredLock) {
            val stale = deferred.entries.filter { Duration.between(it.value.enqueuedAt, now) > deferredMaxAge }
            stale.forEach { deferred.remove(it.key) }
            expired = stale.size
            due = deferred.mapValues { it.value.correlationId }
        }
        if (expired > 0) {
            log.warning(
                "dropped $expired deferred webhook signatures that stayed unstageable past $deferredMaxAge — " +
                    "a transaction that never finalizes cannot be retried forever",
            )
        }
        if (due.isEmpty()) return WebhookRecheck(0, expired, 0)

        val finalized = finality.finalizedSignatures(due.keys)
        val canonical = finalized.mapNotNull { sig -> transactions.fetch(sig)?.let { sig to it } }
        if (canonical.isEmpty()) return WebhookRecheck(0, expired, synchronized(deferredLock) { deferred.size })

        val watched = store.activeWatchedAddresses(chain).mapTo(hashSetOf()) { it.address }
        val observedAt = clock.instant()
        val ingestionRunId = UUID.randomUUID()
        var staged = 0
        val done = mutableSetOf<String>()
        for ((sig, tx) in canonical) {
            val legs = parseAll(listOf(tx), watched, observedAt).flatMap { it.legs }
            staged += store.insertTransfers(legs, ingestionRunId, due.getValue(sig), actor)
            done += sig
        }
        synchronized(deferredLock) {
            done.forEach(deferred::remove)
            return WebhookRecheck(staged, expired, deferred.size)
        }
    }

    /** How many signatures are currently held for re-check. */
    fun deferredSize(): Int = synchronized(deferredLock) { deferred.size }

    /** Hold [signature] for re-check unless the set is full. Re-holding a held signature keeps its first-seen age. */
    private fun hold(
        signature: String,
        correlationId: UUID,
    ): Boolean =
        synchronized(deferredLock) {
            if (signature in deferred || deferred.size < maxDeferred) {
                deferred.putIfAbsent(signature, DeferredSignature(clock.instant(), correlationId))
                true
            } else {
                false
            }
        }

    private fun parseAll(
        canonical: Collection<JsonNode>,
        watched: Set<String>,
        observedAt: Instant,
    ): List<TransferParse> {
        val parses =
            canonical.flatMap { tx ->
                watchedAccounts(tx, watched).map { normalizer.normalize(tx, it, observedAt) }
            }
        val skipped = parses.flatMap { it.skipped }
        if (skipped.isNotEmpty()) {
            log.warning("skipped ${skipped.size} malformed transfer legs: ${skipped.take(5).joinToString()}")
        }
        return parses
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
     * Watched wallets this transaction is relevant to. An `accountKeys` entry that is a
     * watched address matches directly — entries are plain strings in the json encoding and
     * `{"pubkey": ...}` objects in jsonParsed, so accept both. An inbound SPL transfer never
     * lists the recipient wallet among `accountKeys` — only its token account — so the wallet
     * is matched as the `owner` of a `preTokenBalances`/`postTokenBalances` entry instead, the
     * same field the normalizer filters token legs on (#484). A wallet with two ATAs in one
     * transaction still normalizes once — the deterministic external ids keep a second
     * normalization from writing anything new.
     */
    private fun watchedAccounts(
        tx: JsonNode,
        watched: Set<String>,
    ): List<String> {
        val fromKeys =
            tx
                .path("transaction")
                .path("message")
                .path("accountKeys")
                .mapNotNull { key -> if (key.isTextual) key.asText() else key.path("pubkey").asText(null) }
                .filter { it in watched }
        val meta = tx.path("meta")
        val fromOwners =
            meta
                .path("preTokenBalances")
                .mapNotNull { it.path("owner").asText(null) }
                .plus(meta.path("postTokenBalances").mapNotNull { it.path("owner").asText(null) })
                .filter { it in watched }
        return (fromKeys + fromOwners).distinct()
    }

    companion object {
        const val DEFAULT_MAX_DEFERRED = 10_000
        val DEFAULT_DEFERRED_MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}
