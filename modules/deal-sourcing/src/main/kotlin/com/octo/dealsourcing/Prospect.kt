package com.octo.dealsourcing

import java.time.Instant
import java.util.UUID

/** How a prospect entered the pipeline. The closed set keeps intake channels queryable. */
enum class ProspectSource(
    val wireValue: String,
) {
    MANUAL("manual"),
    CRM("crm"),
    EVENT("event"),
    REFERRAL("referral"),
    INBOUND("inbound"),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/** Pipeline stages. Registration lands in SOURCED; INVESTED and PASSED end the prospect. */
enum class ProspectStage(
    val wireValue: String,
    val terminal: Boolean,
) {
    SOURCED("sourced", false),
    SCREENING("screening", false),
    DUE_DILIGENCE("due-diligence", false),
    IC_REVIEW("ic-review", false),
    INVESTED("invested", true),
    PASSED("passed", true),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/** A registered prospect, immutable once stored. Its stage is never stored — only derived from events. */
data class Prospect(
    val id: UUID,
    val tenantId: UUID,
    val name: String,
    val source: ProspectSource,
    val sector: String?,
    val region: String?,
    val description: String?,
    val registeredAt: Instant,
    val sourceRef: String? = null,
) {
    init {
        require(name.isNotBlank()) { "a prospect must name itself" }
    }
}

/**
 * A fact about a prospect. `advanced` moves forward one open stage; `passed` and `invested` are
 * terminal decisions that must carry a rationale — a pipeline that cannot say why it declined is
 * an audit gap (the V8/V14 convention).
 */
sealed interface ProspectEvent {
    val actor: String
    val at: Instant

    data class Advanced(
        override val actor: String,
        override val at: Instant,
        val from: ProspectStage,
        val to: ProspectStage,
        /** The evidence checklist a `due-diligence` landing claims — the lineage a losing race reads back. */
        val taskId: UUID? = null,
    ) : ProspectEvent {
        init {
            require(taskId == null || to == ProspectStage.DUE_DILIGENCE) { "only a due-diligence landing claims a checklist" }
        }
    }
    ) : ProspectEvent

    data class Passed(
        override val actor: String,
        override val at: Instant,
        val from: ProspectStage,
        val rationale: String,
    ) : ProspectEvent {
        init {
            require(rationale.isNotBlank()) { "a pass needs a rationale" }
        }
    }

    /**
     * [taskId] is the `workflow_task` of kind `approval` whose APPROVED verdict authorizes the
     * investment — segregation of duties is enforced by the task itself (approver ≠ requester,
     * Task.kt) and the event keeps the lineage auditable (V19).
     */
    data class Invested(
        override val actor: String,
        override val at: Instant,
        val rationale: String,
        val taskId: UUID,
    ) : ProspectEvent {
        init {
            require(rationale.isNotBlank()) { "an investment decision needs a rationale" }
        }
    }
}

data class ProspectState(
    val prospect: Prospect,
    val stage: ProspectStage,
    val decidedBy: String?,
    val lastEventAt: Instant,
) {
    val terminal get() = stage.terminal
}

/** A new prospect, before any event: registered but not yet screened. */
fun registered(prospect: Prospect): ProspectState =
    ProspectState(prospect, ProspectStage.SOURCED, decidedBy = null, lastEventAt = prospect.registeredAt)

/** The state after [events], in order. Throws on any transition [next] rejects. */
fun replay(
    prospect: Prospect,
    events: List<ProspectEvent>,
): ProspectState = events.fold(registered(prospect)) { state, event -> state.next(event) }

/**
 * Applies one event: forward moves must leave the current stage and land one stage ahead
 * (`sourced → screening → due-diligence → ic-review → invested`; any open stage may `passed`),
 * terminal stages accept nothing, and every transition needs an actor and non-decreasing time.
 */
fun ProspectState.next(event: ProspectEvent): ProspectState {
    require(!stage.terminal) { "prospect ${prospect.id} is already $stage" }
    require(event.actor.isNotBlank()) { "every event needs an actor" }
    require(!event.at.isBefore(lastEventAt)) { "events must be in time order" }

    return when (event) {
        is ProspectEvent.Advanced -> {
            require(event.from == stage) { "prospect ${prospect.id} is $stage, not ${event.from}" }
            require(event.to.ordinal == stage.ordinal + 1) {
                "${event.to} is not the stage after $stage — the pipeline moves one stage at a time"
            }
            require(!event.to.terminal) { "advance reaches only open stages; decide with invested or passed" }
            copy(stage = event.to, lastEventAt = event.at)
        }

        is ProspectEvent.Passed -> {
            require(event.from == stage) { "prospect ${prospect.id} is $stage, not ${event.from}" }
            copy(stage = ProspectStage.PASSED, decidedBy = event.actor, lastEventAt = event.at)
        }

        is ProspectEvent.Invested -> {
            require(stage == ProspectStage.IC_REVIEW) { "only ic-review can invest; prospect ${prospect.id} is $stage" }
            copy(stage = ProspectStage.INVESTED, decidedBy = event.actor, lastEventAt = event.at)
        }
    }
}
