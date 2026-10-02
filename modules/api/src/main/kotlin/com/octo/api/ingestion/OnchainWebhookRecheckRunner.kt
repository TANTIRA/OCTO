package com.octo.api.ingestion

import com.octo.ingestion.onchain.OnchainWebhookService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicInteger

/**
 * Re-checks webhook signatures the service held because they were not finalized yet or their
 * canonical copy could not be fetched (#483). Helius only retries a delivery three times one
 * second apart, which is far inside the ~13 s confirmation→finalization gap, so the retry has
 * to come from our side. A pass is idempotent for the same reason promotion is — the
 * `(source_system, external_id)` key dedupes anything already staged. A failed pass logs and
 * waits for the next tick; held entries stay held.
 */
class OnchainWebhookRecheckRunner(
    private val webhookService: OnchainWebhookService,
    meters: MeterRegistry?,
) {
    private val log = LoggerFactory.getLogger(OnchainWebhookRecheckRunner::class.java)
    private val staged = meters?.let { Counter.builder("onchain.webhook.deferred.staged").register(it) }
    private val expired = meters?.let { Counter.builder("onchain.webhook.deferred.expired").register(it) }
    private val errors = meters?.let { Counter.builder("onchain.webhook.deferred.errors").register(it) }
    private val pending = AtomicInteger().also { meters?.gauge("onchain.webhook.deferred.pending", it) }

    @Scheduled(
        fixedDelayString = "\${octo.onchain.webhook.recheck-ms:5000}",
        initialDelayString = "\${octo.onchain.webhook.recheck-ms:5000}",
    )
    fun recheck() {
        try {
            val report = webhookService.recheckDeferred()
            staged?.increment(report.staged.toDouble())
            expired?.increment(report.expired.toDouble())
            pending.set(report.pending)
            if (report.staged > 0) {
                log.info("webhook re-check staged {} transfer(s); {} signature(s) still pending", report.staged, report.pending)
            }
            if (report.expired > 0) {
                log.warn("webhook re-check expired {} signature(s) that never became stageable", report.expired)
            }
        } catch (e: Exception) {
            errors?.increment()
            log.warn("webhook re-check pass failed: {}: {}", e.javaClass.simpleName, e.message)
        }
    }
}
