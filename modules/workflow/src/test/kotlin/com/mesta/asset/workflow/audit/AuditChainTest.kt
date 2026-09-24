package com.mesta.asset.workflow.audit

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000001")

private fun entry(actor: String = "Rāde") =
    AuditEntry(Instant.parse("2026-09-24T10:00:00Z"), actor, "task.approved", "workflow-task", "t-1", CORRELATION, """{"task": "t-1"}""")

/** A valid chain, linked the way V6's trigger links it. */
private fun chain(vararg entries: AuditEntry): List<AuditRecord> {
    var previous = ByteArray(32)
    return entries.mapIndexed { index, entry ->
        val recordedAt = Instant.parse("2026-09-24T10:00:00.123456Z").plusSeconds(index.toLong())
        val hash = linkHash(AuditRecord(index + 1L, entry, recordedAt, previous, ByteArray(32)))
        AuditRecord(index + 1L, entry, recordedAt, previous, hash).also { previous = hash }
    }
}

private fun AuditRecord.with(
    seq: Long = this.seq,
    entry: AuditEntry = this.entry,
    prevHash: ByteArray = this.prevHash,
    hash: ByteArray = this.hash,
) = AuditRecord(seq, entry, recordedAt, prevHash, hash)

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

class AuditChainTest {
    @Test
    fun `the link hash matches an independent sha256 of the canonical bytes`() {
        // Python hashlib over 32 zero bytes + "1:1" "16:1790244000000000" "16:1790244000123456" "5:Rāde" ...,
        // where "Rāde" is 5 bytes because ā takes two in UTF-8: the prefix counts bytes, as V6's octet_length does.
        assertEquals("376e982bd29e4c7e0cf1df77b63ecff598e843e582ea7fdb52dc273eb7c7f9c6", chain(entry()).single().hash.hex())
    }

    @Test
    fun `an intact chain verifies, and so does an empty one`() {
        assertNull(verifyAuditChain(chain(entry("a"), entry("b"), entry("c"))))
        assertNull(verifyAuditChain(emptyList()))
    }

    @Test
    fun `a changed field breaks its own link`() {
        val records = chain(entry("a"), entry("b"), entry("c")).toMutableList()
        records[1] = records[1].with(entry = entry("mallory"))

        val broken = assertNotNull(verifyAuditChain(records))
        assertEquals(2, broken.seq)
        assertContains(broken.reason, "contents")
    }

    @Test
    fun `a rehashed forgery breaks the next link`() {
        val records = chain(entry("a"), entry("b"), entry("c")).toMutableList()
        val forged = records[1].with(entry = entry("mallory"))
        records[1] = forged.with(hash = linkHash(forged))

        assertEquals(ChainBreak(3, "prev_hash does not match the previous row's hash"), verifyAuditChain(records))
    }

    @Test
    fun `a missing, reordered or re-rooted row is found`() {
        val records = chain(entry("a"), entry("b"), entry("c"))

        assertEquals(2, verifyAuditChain(listOf(records[0], records[2]))?.seq)
        assertEquals(2, verifyAuditChain(listOf(records[0], records[2], records[1]))?.seq)
        assertEquals(1, verifyAuditChain(listOf(records[0].with(prevHash = ByteArray(32) { 1 })))?.seq)
    }

    @Test
    fun `an entry needs an actor, an action and a subject`() {
        assertFailsWith<IllegalArgumentException> { entry(actor = " ") }
    }
}
