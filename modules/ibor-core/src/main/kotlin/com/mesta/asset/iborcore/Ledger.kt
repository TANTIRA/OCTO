package com.mesta.asset.iborcore

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.UUID

/**
 * Mirrors the `flow-type` @values in `ontology/mesta-investment.tql` and the check constraint on
 * `mesta.ledger_event`. [investorFlow] marks the types that enter the investor cash-flow series;
 * fees, expenses, carry and other income are reported but excluded (owner decision on #6).
 */
enum class FlowType(
    val wireValue: String,
    val investorFlow: Boolean,
) {
    CONTRIBUTION("contribution", true),
    DISTRIBUTION("distribution", true),
    RECALLABLE_DISTRIBUTION("recallable-distribution", true),
    MANAGEMENT_FEE("management-fee", false),
    EXPENSE("expense", false),
    CARRIED_INTEREST("carried-interest", false),
    OTHER_INCOME("other-income", false),
}

/** One row of `mesta.ledger_event`. [amount] is investor-signed: contributions negative. */
data class LedgerEvent(
    val id: UUID,
    val flowType: FlowType,
    val amount: BigDecimal,
    val currency: Currency,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val supersedesId: UUID? = null,
)

/**
 * The ledger as it was known at [knownAt]: rows recorded later are ignored, and every row that a
 * remaining row supersedes is dropped, so correction chains resolve to their last link.
 */
fun currentEvents(
    ledger: List<LedgerEvent>,
    knownAt: Instant,
): List<LedgerEvent> = resolveCurrent(ledger, knownAt, LedgerEvent::id, LedgerEvent::supersedesId, LedgerEvent::recordedAt)

/** Shared supersession rules for every append-only fact table: bi-temporal cut, chains, no forks. */
internal fun <T> resolveCurrent(
    rows: List<T>,
    knownAt: Instant,
    id: (T) -> UUID,
    supersedesId: (T) -> UUID?,
    recordedAt: (T) -> Instant,
): List<T> {
    val known = rows.filter { !recordedAt(it).isAfter(knownAt) }
    val ids = rows.mapTo(HashSet(), id)
    val superseded = HashSet<UUID>()
    for (row in known) {
        val target = supersedesId(row) ?: continue
        check(target in ids) { "${id(row)} supersedes $target, which is not loaded" }
        check(superseded.add(target)) { "$target is superseded more than once" }
    }
    return known.filter { id(it) !in superseded }
}

/**
 * Commitment-level IBOR position derived from the ledger (ADR-0001 §3). Amounts are positive totals
 * per flow type; [called] is paid-in capital. [cashFlows] holds only investor flows, signed, dated
 * in the caller's zone and sorted by date.
 */
data class CommitmentPosition(
    val currency: Currency,
    val called: BigDecimal,
    val distributed: BigDecimal,
    val recallableDistributed: BigDecimal,
    val fees: BigDecimal,
    val expenses: BigDecimal,
    val carriedInterest: BigDecimal,
    val otherIncome: BigDecimal,
    val cashFlows: List<Pair<LocalDate, BigDecimal>>,
)

/**
 * Derives the position of one commitment. Attribution lives in TypeDB (ADR-0003), so the caller
 * passes the ids of the events attributed to the commitment as [members]. Supersession is resolved
 * over [ledger] first, so a correction that moves an event elsewhere removes it here.
 */
fun commitmentPosition(
    ledger: List<LedgerEvent>,
    members: Set<UUID>,
    knownAt: Instant,
    zone: ZoneId,
): CommitmentPosition {
    val events = currentEvents(ledger, knownAt).filter { it.id in members }
    require(events.isNotEmpty()) { "no current ledger events for this commitment" }
    val currency = events.map { it.currency }.distinct().singleOrNull()
    requireNotNull(currency) { "a commitment's events must share one currency" }

    fun total(type: FlowType) = events.filter { it.flowType == type }.sumOf { it.amount }.abs()

    return CommitmentPosition(
        currency = currency,
        called = total(FlowType.CONTRIBUTION),
        distributed = total(FlowType.DISTRIBUTION),
        recallableDistributed = total(FlowType.RECALLABLE_DISTRIBUTION),
        fees = total(FlowType.MANAGEMENT_FEE),
        expenses = total(FlowType.EXPENSE),
        carriedInterest = total(FlowType.CARRIED_INTEREST),
        otherIncome = total(FlowType.OTHER_INCOME),
        cashFlows =
            events
                .filter { it.flowType.investorFlow }
                .map { it.occurredAt.atZone(zone).toLocalDate() to it.amount }
                .sortedBy { it.first },
    )
}
