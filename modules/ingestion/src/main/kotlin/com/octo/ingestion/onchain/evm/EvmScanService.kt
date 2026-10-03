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
 * The scan runs `newestStagedSlot + 1 -> finalized head` in bounded windows. The cursor is
 * derived from staging, never stored: a crashed window re-scans and the
 * `(source_system, external_id)` key refuses duplicates. A provider that rejects the range
 * (its own cap, not the protocol's) shrinks the window rather than failing the run.
 *
 * Known limitation, by design: the cursor is per chain, so a wallet watched *after* the
 * cursor passed its history sees only forward movement. Backfill is a startBlock reset or
 * a dedicated tool — recorded in the runbook, not silently approximated.
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

        var from = maxOf((store.newestStagedSlot(config.chain) ?: -1L) + 1, config.startBlock)
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
            // cursor still advances past it (per-chain staging cursor), so the runbook surfaces
            // the gap rather than silently re-scanning or fabricating identity (#196's rule).
            val txHash = log.path("transactionHash").asText()
            val logIndex = log.path("logIndex").asQuantityOrNull()
            val block = log.path("blockNumber").asQuantityOrNull()?.toLong()
            val contract = log.path("address").asText().lowercase()
            if (txHash.isBlank() || logIndex == null || block == null || contract.isBlank()) {
                malformed += describe(log)
                continue
            }
            // A contract that cannot report decimals is skipped and named in the report. A
            // deterministic VM failure (invalid opcode, out of gas, invalid jump) is that case,
            // not a provider outage: shrinking the window and then failing the run would pin the
            // cursor on this block and stop ingestion for every watched wallet on the chain (#545).
            val resolved =
                try {
                    decimals.resolve(contract)
                } catch (e: EvmException) {
                    if (!e.isUnreadableContract()) throw e
                    null
                }
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
