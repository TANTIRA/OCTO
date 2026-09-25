package com.mesta.asset.recon.matching

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.math.abs

/** Matching rules every reconciliation below is computed under (#107, #6 slice 9). */
const val RECONCILIATION_RULESET = "source-vs-ibor matching v1"

/** One record as the source system reports it. [externalId] is the source's own id, the key `ledger_event` stores. */
data class SourceRecord(
    val sourceSystem: String,
    val externalId: String,
    val amount: BigDecimal,
    val currency: Currency,
    val date: LocalDate,
) {
    init {
        require(sourceSystem.isNotBlank() && externalId.isNotBlank()) { "a source record names its system and id" }
    }
}

/** One current `ledger_event` row as the IBOR holds it, dated in the caller's zone. */
data class IborRecord(
    val id: UUID,
    val sourceSystem: String,
    val externalId: String?,
    val amount: BigDecimal,
    val currency: Currency,
    val date: LocalDate,
)

/** What counts as equal. Amounts within [amount] and dates within [days] match; currency must be identical. */
data class Tolerance(
    val amount: BigDecimal,
    val days: Int,
) {
    init {
        require(amount.signum() >= 0 && days >= 0) { "tolerances must not be negative" }
    }

    companion object {
        val EXACT = Tolerance(BigDecimal.ZERO, 0)
    }
}

enum class BreakKind(
    val wireValue: String,
) {
    MISSING_IN_IBOR("missing-in-ibor"),
    MISSING_IN_SOURCE("missing-in-source"),
    AMOUNT_MISMATCH("amount-mismatch"),
    DATE_MISMATCH("date-mismatch"),
    CURRENCY_MISMATCH("currency-mismatch"),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/** One discrepancy. Exactly the side that exists is set for a missing record; both for a mismatch. [detail] is what differed, as strings. */
data class Break(
    val kind: BreakKind,
    val sourceSystem: String,
    val sourceRef: String?,
    val ledgerEventId: UUID?,
    val detail: Map<String, String>,
)

data class Reconciliation(
    val matched: List<Pair<SourceRecord, IborRecord>>,
    val breaks: List<Break>,
    val ruleset: String = RECONCILIATION_RULESET,
)

/**
 * Source-versus-IBOR matching on `(sourceSystem, externalId)` (#107): every source record finds the ledger event
 * carrying its id, and every ledger event of the same source systems must be claimed by a source record. A matched
 * pair is compared under [tolerance] and can raise one break per differing field. Pure; the caller turns each
 * break into an evidence-request task. Ledger events without an external id cannot be matched and are reported
 * as missing in source, because an unattributable event is itself a finding.
 */
fun reconcile(
    source: List<SourceRecord>,
    ibor: List<IborRecord>,
    tolerance: Tolerance = Tolerance.EXACT,
): Reconciliation {
    require(
        source.map { it.sourceSystem to it.externalId }.toSet().size == source.size,
    ) { "source records must be unique per (system, id)" }
    val systems = source.map { it.sourceSystem }.toSet()
    val byKey = ibor.filter { it.externalId != null }.groupBy { it.sourceSystem to it.externalId!! }
    byKey.values
        .firstOrNull {
            it.size > 1
        }?.let { error("ledger holds ${it.size} current events for ${it.first().sourceSystem}:${it.first().externalId}; supersede one") }

    val matched = mutableListOf<Pair<SourceRecord, IborRecord>>()
    val breaks = mutableListOf<Break>()
    val claimed = mutableSetOf<UUID>()
    for (record in source) {
        val event = byKey[record.sourceSystem to record.externalId]?.single()
        if (event == null) {
            breaks +=
                Break(
                    BreakKind.MISSING_IN_IBOR,
                    record.sourceSystem,
                    record.externalId,
                    null,
                    mapOf("amount" to record.amount.toPlainString(), "date" to record.date.toString()),
                )
            continue
        }
        claimed += event.id
        matched += record to event
        if (record.currency != event.currency) {
            breaks +=
                Break(
                    BreakKind.CURRENCY_MISMATCH,
                    record.sourceSystem,
                    record.externalId,
                    event.id,
                    mapOf("source" to record.currency.currencyCode, "ibor" to event.currency.currencyCode),
                )
        }
        if ((record.amount - event.amount).abs() > tolerance.amount) {
            breaks +=
                Break(
                    BreakKind.AMOUNT_MISMATCH,
                    record.sourceSystem,
                    record.externalId,
                    event.id,
                    mapOf("source" to record.amount.toPlainString(), "ibor" to event.amount.toPlainString()),
                )
        }
        if (abs(record.date.toEpochDay() - event.date.toEpochDay()) > tolerance.days) {
            breaks +=
                Break(
                    BreakKind.DATE_MISMATCH,
                    record.sourceSystem,
                    record.externalId,
                    event.id,
                    mapOf(
                        "source" to record.date.toString(),
                        "ibor" to event.date.toString(),
                    ),
                )
        }
    }
    for (event in ibor) {
        if (event.sourceSystem !in systems || event.id in claimed) continue
        breaks +=
            Break(
                BreakKind.MISSING_IN_SOURCE,
                event.sourceSystem,
                event.externalId,
                event.id,
                mapOf("amount" to event.amount.toPlainString(), "date" to event.date.toString()),
            )
    }
    return Reconciliation(matched, breaks)
}
