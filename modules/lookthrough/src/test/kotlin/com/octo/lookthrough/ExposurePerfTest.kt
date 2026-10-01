package com.octo.lookthrough

import java.math.BigDecimal
import java.util.Currency
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0004's last open acceptance box: "look-through aggregation over 5-level hierarchy within
 * reporting SLA". `lookThrough`'s own comment flags the risk this guards against — path
 * enumeration is exponential on deep, diamond-shaped graphs ("switch to a topological sum ...
 * when real hierarchies get that dense"). This builds exactly that shape — 5 ownership levels,
 * fan-out 10 at every level, with every level-5 edge converging onto a pool of 200 terminal
 * companies — and asserts both correctness (exposure is conserved end to end) and a wall-clock
 * bound, so a future change that makes the exponential case worse fails CI instead of surfacing
 * as a slow report query in production.
 */
class ExposurePerfTest {
    private val usd = Currency.getInstance("USD")

    /** Fraction per child so every node's outgoing ownership sums to exactly 1 (exact in BigDecimal: 1/10). */
    private val fanOut = 10
    private val levels = 5
    private val terminalPoolSize = 200
    private val childFraction = BigDecimal.ONE.divide(BigDecimal(fanOut))

    private fun buildHierarchy(): List<OwnershipEdge> {
        val edges = mutableListOf<OwnershipEdge>()
        var frontier = listOf("root")
        for (level in 1..levels) {
            val next = mutableListOf<String>()
            for (parent in frontier) {
                for (slot in 0 until fanOut) {
                    val child =
                        if (level == levels) {
                            // Terminal level: converge onto a small pool so most paths overlap —
                            // the diamond shape the exponential-path-count comment warns about.
                            "L$level-${(parent.hashCode() * 31 + slot).mod(terminalPoolSize)}"
                        } else {
                            "L$level-$parent-$slot"
                        }
                    edges += OwnershipEdge(parent, child, childFraction)
                    next += child
                }
            }
            frontier = next.distinct()
        }
        return edges
    }

    @Test
    fun `a 5-level hierarchy converging onto 200 companies resolves correctly within the reporting SLA`() {
        val edges = buildHierarchy()
        // fanOut^levels leaf edges at the deepest level alone; total edges across all levels is larger still.
        assertTrue(edges.size > 100_000, "expected a stress-sized graph, got ${edges.size} edges")

        val rootNav = BigDecimal("1000000")
        var report: ExposureReport? = null
        val elapsedNanos = measureNanoTime { report = lookThrough("root", rootNav, usd, edges) }

        val result = requireNotNull(report)
        // Every level's children own exactly 100% of their parent (fanOut equal fractions), so
        // look-through is lossless: the terminal exposures must sum back to the root NAV exactly.
        assertEquals(0, rootNav.compareTo(result.byAsset.values.fold(BigDecimal.ZERO, BigDecimal::add)))
        // The pool bounds the key space from above; with 100k+ converging paths landing on only
        // 200 ids, most of the pool is reached — assert convergence happened without depending on
        // exact hash-distribution coverage.
        assertTrue(result.byAsset.size in 1..terminalPoolSize)
        assertTrue(
            result.byAsset.size > terminalPoolSize / 2,
            "expected broad convergence onto the terminal pool, got only ${result.byAsset.size} distinct ids",
        )

        // Generous bound for a pure in-memory, no-I/O computation — this is a regression guard
        // against the documented exponential-blowup risk, not a tight benchmark. A CI runner
        // under load should still clear this by a wide margin if the algorithm stays linear-ish
        // in edge count; a real exponential regression would miss it by orders of magnitude.
        val elapsedMillis = elapsedNanos / 1_000_000
        assertTrue(
            elapsedMillis < 5_000,
            "look-through over a 5-level/200-way-converging hierarchy took ${elapsedMillis}ms, expected < 5000ms",
        )
    }
}
