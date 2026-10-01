package com.octo.iborcore

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.UUID

/** Methodology tag stamped on every journal so the mapping version is reproducible (§10.6). */
const val GL_METHODOLOGY = "double-entry journal from investor-signed ledger events; LP chart of accounts v1"

/** A general-ledger account: a stable [code] the accounting system keys on and a human-readable [name]. */
data class GlAccount(
    val code: String,
    val name: String,
)

/**
 * The accounts [glJournal] posts to. Defaults to a limited-partner chart of accounts; a firm on a
 * different chart passes its own codes/names, which keeps the posting rules (which side each flow
 * type lands on) fixed while the account identifiers vary.
 */
data class GlChart(
    val cash: GlAccount = GlAccount("1000", "Cash"),
    val investments: GlAccount = GlAccount("1200", "Investments in Funds"),
    val managementFeeExpense: GlAccount = GlAccount("6000", "Management Fee Expense"),
    val fundExpense: GlAccount = GlAccount("6100", "Fund Expenses"),
    val carriedInterestExpense: GlAccount = GlAccount("6200", "Carried Interest Expense"),
    val otherIncome: GlAccount = GlAccount("4000", "Other Income"),
)

/** The built-in LP chart; callers override per firm. */
val DEFAULT_GL_CHART = GlChart()

/**
 * One balanced journal entry for a single ledger event: [amount] (>= 0) is debited to [debit] and
 * credited to [credit], in [currency], dated on the event's [date]. Direction lives in the debit/credit
 * choice, never the sign of the number — the shape an accounting system ingests.
 */
data class GlLine(
    val sourceEventId: UUID,
    val date: LocalDate,
    val currency: Currency,
    val flowType: FlowType,
    val debit: GlAccount,
    val credit: GlAccount,
    val amount: BigDecimal,
)

/**
 * A double-entry journal derived from the ledger as known at a cut date. [debitsByCurrency] equals
 * [creditsByCurrency] by construction — every entry is balanced — so a consumer can reconcile the feed
 * against zero before loading it into a GL.
 */
data class GlJournal(
    val asOf: LocalDate,
    val lines: List<GlLine>,
    val debitsByCurrency: Map<Currency, BigDecimal>,
    val creditsByCurrency: Map<Currency, BigDecimal>,
    val methodology: String = GL_METHODOLOGY,
)

/**
 * Maps investor-signed ledger events to a double-entry journal. Supersessions are resolved first
 * ([currentEvents]) so a corrected event is never posted twice, then each surviving event becomes one
 * balanced entry keyed off its [FlowType]. Each flow type has a natural investor sign ([naturallyNegative]);
 * an event with that sign posts the type's account pair, and one with the opposite sign — a standalone
 * reversal, a fee rebate, a contribution refund — posts the pair swapped, so it unwinds the original
 * instead of repeating it. The magnitude is the absolute value; direction lives in the debit/credit choice.
 *
 * @param knownAt bi-temporal cut — rows recorded after it, and events a survivor supersedes, drop out.
 * @param zone the accounting zone the occurrence timestamp is dated in.
 */
fun glJournal(
    events: List<LedgerEvent>,
    knownAt: Instant,
    zone: ZoneId,
    chart: GlChart = DEFAULT_GL_CHART,
): GlJournal {
    val asOf = knownAt.atZone(zone).toLocalDate()
    val lines =
        currentEvents(events, knownAt)
            // A journal dated asOf carries only entries dated on or before it: an event recorded early
            // but occurring later (a scheduled call) belongs to a later journal, not this one.
            .filter {
                !it.occurredAt
                    .atZone(zone)
                    .toLocalDate()
                    .isAfter(asOf)
            }.map { event ->
                val (natural, contra) = accounts(event.flowType, chart)
                val sign = event.amount.signum()
                val reversed = sign != 0 && (sign < 0) != naturallyNegative(event.flowType)
                val (debit, credit) = if (reversed) contra to natural else natural to contra
                GlLine(
                    sourceEventId = event.id,
                    date = event.occurredAt.atZone(zone).toLocalDate(),
                    currency = event.currency,
                    flowType = event.flowType,
                    debit = debit,
                    credit = credit,
                    amount = event.amount.abs(),
                )
            }
    return GlJournal(
        asOf = asOf,
        lines = lines,
        debitsByCurrency = totalsByCurrency(lines),
        creditsByCurrency = totalsByCurrency(lines),
    )
}

/** Sums entry magnitudes per currency. Debit and credit totals are equal by construction (each entry balances). */
private fun totalsByCurrency(lines: List<GlLine>): Map<Currency, BigDecimal> {
    val totals = LinkedHashMap<Currency, BigDecimal>()
    for (line in lines) {
        totals[line.currency] = (totals[line.currency] ?: BigDecimal.ZERO) + line.amount
    }
    return totals
}

private fun accounts(
    flowType: FlowType,
    chart: GlChart,
): Pair<GlAccount, GlAccount> =
    when (flowType) {
        FlowType.CONTRIBUTION -> chart.investments to chart.cash
        FlowType.DISTRIBUTION, FlowType.RECALLABLE_DISTRIBUTION -> chart.cash to chart.investments
        FlowType.MANAGEMENT_FEE -> chart.managementFeeExpense to chart.cash
        FlowType.EXPENSE -> chart.fundExpense to chart.cash
        FlowType.CARRIED_INTEREST -> chart.carriedInterestExpense to chart.cash
        FlowType.OTHER_INCOME -> chart.cash to chart.otherIncome
    }

/** The investor sign each flow type normally carries (`Ledger.kt`: contributions negative); exhaustive on purpose. */
private fun naturallyNegative(flowType: FlowType): Boolean =
    when (flowType) {
        FlowType.CONTRIBUTION, FlowType.MANAGEMENT_FEE, FlowType.EXPENSE, FlowType.CARRIED_INTEREST -> true
        FlowType.DISTRIBUTION, FlowType.RECALLABLE_DISTRIBUTION, FlowType.OTHER_INCOME -> false
    }
