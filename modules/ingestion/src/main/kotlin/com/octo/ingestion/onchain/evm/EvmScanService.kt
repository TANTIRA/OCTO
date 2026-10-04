package com.octo.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.octo.ingestion.onchain.OnchainStagingStore
import com.octo.ingestion.onchain.OnchainTransfer
import java.time.Instant
import java.util.UUID

/** What one `scan` pass did, for logs, metrics, and the operator runbook. */
data class EvmScanReport(
    val chain: String,
    val walletsWatched: Int,
    val headBlock: Long,
    val windowsScanned: Int,
    val logsSeen: Int,
    val legsStaged: Int,
    val skippedContracts: List<String>,
    val malformedLogs: List<String>,
    val windowShrinks: Int,
)

/**
 * The EVM poller: instead of walking each wallet's history like the Solana service, one
 * `eth_getLogs` scan covers every watched address at once — two topic-filtered queries per
 * block window (senders and recipients), merged by `(txHash, logIndex)`.
 *
 * The scan runs from the resume block through the finalized head in bounded windows.
 * Resume is one past the stored scanned-through checkpoint. A chain with no checkpoint
 * yet falls back to the newest staged slot, so the first poll after this exists does not
 * replay history that already staged. Once a checkpoint exists it wins, even when a later
 * transfer is staged: following that transfer would skip the blocks in between. Each
 * window that finishes — including one that stages nothing — records its last block, so
 * a quiet period is not scanned again on the next poll. A crashed window is not recorded
 * and re-scans; the `(source_system, external_id)` key refuses duplicates. A provider
 * that rejects the range (its own cap, not the protocol's) shrinks the window rather
 * than failing the run.
 *
 * Known limitation, by design: the checkpoint is per chain, so a wallet watched *after* it
 * passed that wallet's history sees only forward movement. Backfill is a checkpoint reset
 * or a dedicated tool — recorded in the runbook, not silently approximated.
 */
class EvmScanService(
    private val rpc: EvmRpcApi,
    private val normalizer: EvmTransferNormalizer,
    private val store: OnchainStagingStore,
    private val config: EvmConfig,
    private val actor: String = "evm-poller",
) {
    private var chainChecked = false

    fun scan(): EvmScanReport {
        checkChain()
        val watched =
            store
                .activeWatchedAddresses(config.chain)
                .map { it.address.lowercase() }
                .toSet()
        val head = finalizedHead()
        if (watched.isEmpty()) {
            return EvmScanReport(config.chain, 0, head, 0, 0, 0, emptyList(), emptyList(), 0)
        }

        val registered =
            store
                .tokenContracts(config.chain)
                .associate { it.mintAddress.lowercase() to it.decimals }
        val decimals = EvmDecimalsResolver(rpc, registered)
        val blockTimes = mutableMapOf<Long, Instant?>()
        val runId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()

        var windows = 0
        var logsSeen = 0
        var legsStaged = 0
        var shrinks = 0
        val skippedContracts = linkedSetOf<String>()
        val malformedLogs = linkedSetOf<String>()

        var from = resumeFrom()
        var window = config.maxBlockWindow
        while (from <= head) {
            val to = minOf(from + window - 1, head)
            try {
                val outcome = scanWindow(from, to, watched, decimals, blockTimes)
                windows++
                logsSeen += outcome.logs
                legsStaged += store.insertTransfers(outcome.legs, runId, correlationId, actor)
                skippedContracts += outcome.skippedContracts
                malformedLogs += outcome.malformedLogs
                // After the inserts commit. An empty window still counts: that is the quiet
                // range the staged-slot cursor used to rescan on every poll.
                store.recordScannedThrough(config.chain, to)
                from = to + 1
            } catch (e: EvmException) {
                if (e.status != null || window <= 1L) throw e
                window = maxOf(window / 2, 1)
                shrinks++
            }
        }
        return EvmScanReport(
            config.chain,
            watched.size,
            head,
            windows,
            logsSeen,
            legsStaged,
            skippedContracts.toList(),
            malformedLogs.toList(),
            shrinks,
        )
    }

    private fun scanWindow(
        from: Long,
        to: Long,
        watched: Set<String>,
        decimals: EvmDecimalsResolver,
        blockTimes: MutableMap<Long, Instant?>,
    ): WindowOutcome {
        val logs = linkedMapOf<String, JsonNode>()
        val watchedList = watched.toList()
        for (fromSide in listOf(true, false)) {
            rpc
                .transferLogs(from, to, watchedList, fromSide)
                .forEach { log ->
                    val key = "${log.path("transactionHash").asText()}:${log.path("logIndex").asText()}"
                    logs.putIfAbsent(key, log)
                }
        }

        val skipped = mutableListOf<String>()
        val malformed = mutableListOf<String>()
        val legs = mutableListOf<OnchainTransfer>()
        for (log in logs.values) {
            // A log that cannot identify itself is never staged as a fact: no tx hash, log index,
            // or block number means no dedup key and no audit trail. The skip is recorded — the
            // scanned-through checkpoint still advances past it, so the runbook surfaces
            // the gap rather than silently re-scanning or fabricating identity (#196's rule).
            val txHash = log.path("transactionHash").asText()
            val logIndex = log.path("logIndex").asQuantityOrNull()
            val block = log.path("blockNumber").asQuantityOrNull()?.toLong()
            val contract = log.path("address").asText().lowercase()
            if (txHash.isBlank() || logIndex == null || block == null || contract.isBlank()) {
                malformed += describe(log)
                continue
            }
            val resolved = decimals.resolve(contract)
            if (resolved == null) {
                skipped += contract
                continue
            }
            val blockTime =
                blockTimes.computeIfAbsent(block) {
                    rpc
                        .blockByNumber(block)
                        ?.path("timestamp")
                        ?.asQuantityOrNull()
                        ?.let { runCatching { Instant.ofEpochSecond(it.toLong()) }.getOrNull() }
                }
            if (blockTime == null) {
                malformed += "block $block has no readable timestamp (tx $txHash)"
                continue
            }
            legs += normalizer.normalize(log, watched, resolved, blockTime, config.chain, config.sourceSystem)
        }
        return WindowOutcome(logs.size, legs, skipped, malformed)
    }

    /**
     * First block to scan. A stored checkpoint is the cursor. The staged slot is only the
     * floor when no checkpoint exists yet.
     */
    private fun resumeFrom(): Long {
        val checkpoint = store.scannedThrough(config.chain)
        val floor = checkpoint ?: (store.newestStagedSlot(config.chain) ?: -1L)
        return maxOf(floor + 1, config.startBlock)
    }

    /** One eth_chainId check per process — a wrong-endpoint deploy fails on the first scan. */
    private fun checkChain() {
        if (chainChecked) return
        val actual = rpc.chainId()
        require(actual == config.chainId) {
            "rpc endpoint reports chainId $actual, expected ${config.chainId} (${config.chain})"
        }
        chainChecked = true
    }

    private fun finalizedHead(): Long {
        val block = rpc.finalizedBlock()
        val number = block.takeIf { it.isObject }?.path("number")
        if (number == null || !number.isTextual) {
            throw EvmException("endpoint does not expose a 'finalized' block tag for ${config.chain}")
        }
        return number
            .asQuantityOrNull()
            ?.toLong()
            ?: throw EvmException("endpoint returned a malformed 'finalized' block number for ${config.chain}")
    }

    /** A human-readable descriptor for a log whose identity fields were unreadable. */
    private fun describe(log: JsonNode): String {
        val tx = log.path("transactionHash").asText().ifBlank { "?" }
        val idx = log.path("logIndex").asText().ifBlank { "?" }
        val block = log.path("blockNumber").asText().ifBlank { "?" }
        return "log $idx of tx $tx at block $block is missing identity fields"
    }

    private data class WindowOutcome(
        val logs: Int,
        val legs: List<OnchainTransfer>,
        val skippedContracts: List<String>,
        val malformedLogs: List<String>,
    )
}
