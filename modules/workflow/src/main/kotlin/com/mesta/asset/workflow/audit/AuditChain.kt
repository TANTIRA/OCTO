package com.mesta.asset.workflow.audit

import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What happened, who did it, and to what (data-security-governance.md, "Logging, audit, and monitoring").
 * [details] is a JSON object of references (ids, hashes, versions), never a sensitive payload.
 */
data class AuditEntry(
    val occurredAt: Instant,
    val actor: String,
    val action: String,
    val subjectType: String,
    val subjectId: String,
    val correlationId: UUID,
    val details: String = "{}",
) {
    init {
        require(listOf(actor, action, subjectType, subjectId).none { it.isBlank() }) { "actor, action and subject must not be blank" }
    }
}

/** One stored link of `mesta.audit_event`. Read from the database, [entry] carries details in its jsonb text form. */
class AuditRecord(
    val seq: Long,
    val entry: AuditEntry,
    val recordedAt: Instant,
    val prevHash: ByteArray,
    val hash: ByteArray,
)

/** The first link that fails to verify, and why. */
data class ChainBreak(
    val seq: Long,
    val reason: String,
)

private val GENESIS = ByteArray(32)

/**
 * Recomputes every link of the whole chain, in seq order, and returns the first that does not verify, or null
 * when it is intact. It catches a changed field, a missing or reordered row, and a forged hash. It cannot catch
 * the newest rows being cut off; that needs the head hash anchored outside the database (V6's known ceiling).
 */
fun verifyAuditChain(records: List<AuditRecord>): ChainBreak? {
    var previous = GENESIS
    for ((index, record) in records.withIndex()) {
        val expectedSeq = index + 1L
        when {
            record.seq != expectedSeq -> return ChainBreak(expectedSeq, "found seq ${record.seq}: a row is missing or out of order")
            !record.prevHash.contentEquals(previous) -> return ChainBreak(record.seq, "prev_hash does not match the previous row's hash")
            !record.hash.contentEquals(linkHash(record)) -> return ChainBreak(record.seq, "hash does not match the row's contents")
        }
        previous = record.hash
    }
    return null
}

/** sha256(prev_hash ‖ canonical row), over the same bytes as `mesta.audit_event_canonical` in V6. */
internal fun linkHash(record: AuditRecord): ByteArray {
    val entry = record.entry
    val fields =
        listOf(
            record.seq.toString(),
            micros(entry.occurredAt),
            micros(record.recordedAt),
            entry.actor,
            entry.action,
            entry.subjectType,
            entry.subjectId,
            entry.correlationId.toString(),
            entry.details,
        )
    val canonical = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
    return MessageDigest.getInstance("SHA-256").run {
        update(record.prevHash)
        digest(canonical.toByteArray(Charsets.UTF_8))
    }
}

private fun micros(instant: Instant) = ChronoUnit.MICROS.between(Instant.EPOCH, instant).toString()
