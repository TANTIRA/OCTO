package com.mesta.asset.iborcore

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/** Mirrors the `valuation-method` @values in `ontology/mesta-investment.tql` and `mesta.valuation_event`. */
enum class ValuationMethod(
    val wireValue: String,
) {
    DCF("dcf"),
    COMPARABLES("comparables"),
    LBO("lbo"),
    COST("cost"),
    RECENT_ROUND("recent-round"),
    MARK_TO_MODEL("mark-to-model"),
    OTHER("other"),
    ;

    companion object {
        fun fromWireValue(value: String): ValuationMethod =
            entries.firstOrNull { it.wireValue == value } ?: error("unknown valuation method: $value")
    }
}

/** One row of `mesta.valuation_event`. The valued subject is attributed in TypeDB (`valuation-of`). */
data class ValuationEvent(
    val id: UUID,
    val amount: BigDecimal,
    val currency: Currency,
    val asOfDate: LocalDate,
    val method: ValuationMethod?,
    val recordedAt: Instant,
    val supersedesId: UUID? = null,
)

/**
 * The NAV in force on [asOf] as known at [knownAt]: latest current valuation among [members] dated on or
 * before [asOf]. Null when there is none; two current valuations on that date fail rather than pick one.
 */
fun latestValuation(
    valuations: List<ValuationEvent>,
    members: Set<UUID>,
    asOf: LocalDate,
    knownAt: Instant,
): ValuationEvent? {
    val candidates =
        resolveCurrent(valuations, knownAt, ValuationEvent::id, ValuationEvent::supersedesId, ValuationEvent::recordedAt)
            .filter { it.id in members && !it.asOfDate.isAfter(asOf) }
    val latestDate = candidates.maxOfOrNull { it.asOfDate } ?: return null
    val onDate = candidates.filter { it.asOfDate == latestDate }
    check(onDate.size == 1) { "${onDate.size} current valuations on $latestDate; supersede one of them" }
    return onDate.single()
}
