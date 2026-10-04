package com.octo.api.ingestion

import com.octo.iborcore.InstrumentFlowPromoter
import com.octo.iborcore.PromotionReport
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicInteger

/**
 * Promotes finalized staging rows into `instrument_flow` on a fixed delay (#310) — the one
 * trigger for every staging route (Helius webhook, EVM poller), so promotion never depends on
 * which collector wrote the fact. A pass is idempotent: the `(source_system, external_id)` key
 * makes a replay, a crashed pass, or two replicas racing a no-op, so no claim/lease is needed.
 * A failed pass logs and waits for the next tick; nothing it wrote needs undoing.
 */
class OnchainPromotionRunner(
    private val promoter: InstrumentFlowPromoter,
    meters: MeterRegistry?,
) {
    private val log = LoggerFactory.getLogger(OnchainPromotionRunner::class.java)
    private val promoted = meters?.let { Counter.builder("onchain.promotion.promoted").register(it) }
    private val errors = meters?.let { Counter.builder("onchain.promotion.errors").register(it) }
    private val quarantined = AtomicInteger().also { meters?.gauge("onchain.promotion.quarantined", it) }
    private val deferred = AtomicInteger().also { meters?.gauge("onchain.promotion.deferred", it) }

    @Scheduled(
        fixedDelayString = "\${octo.onchain.promotion.poll-ms:30000}",
        initialDelayString = "\${octo.onchain.promotion.initial-delay-ms:30000}",
    )
    fun poll() {
        try {
            record(promoter.promote())
        } catch (e: Exception) {
            errors?.increment()
            log.warn("onchain promotion pass failed: {}: {}", e.javaClass.simpleName, e.message)
        }
    }

    private fun record(report: PromotionReport) {
        promoted?.increment(report.promoted.toDouble())
        deferred.set(report.deferred.size)
        val waiting = report.quarantined.sumOf { it.count }
        // Quarantine persists until someone registers the instrument — warn when it changes, not every tick.
        if (quarantined.getAndSet(waiting) != waiting && waiting > 0) {
            log.warn(
                "onchain promotion: {} staged transfers wait on unregistered instruments {}",
                waiting,
                report.quarantined.map { "${it.chain}:${it.mintAddress ?: "native"}" },
            )
        }
        if (report.promoted > 0) log.info("onchain promotion: {} flows promoted, {} deferred", report.promoted, report.deferred.size)
    }
}
