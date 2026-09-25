package com.mesta.asset.api.asset

import java.time.Instant
import java.util.UUID

/** The ontology types that can hold assets (#6 decision 3). Listed and onchain instruments are #109/#110. */
enum class AssetType(
    val wireValue: String,
) {
    FUND("fund"),
    INVESTMENT("investment"),
    OPERATING_COMPANY("operating-company"),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/** One external identifier: `lei`, `isin`, `crm`, `vendor:preqin`, … */
data class Identifier(
    val scheme: String,
    val value: String,
)

/** One row of `mesta.asset` (V11). A correction is a new row that supersedes this one with a rationale. */
data class Asset(
    val id: UUID,
    val tenantId: UUID,
    val type: AssetType,
    val assetClass: String,
    val displayName: String,
    val region: String? = null,
    val tags: List<String> = emptyList(),
    val typedbIid: String? = null,
    val supersedesId: UUID? = null,
    val rationale: String? = null,
)

/** An asset as read back: its identifiers across the whole lineage, and the row that replaced it, if any. */
data class AssetRecord(
    val asset: Asset,
    val identifiers: List<Identifier>,
    val supersededBy: UUID?,
    val recordedAt: Instant,
)

/** Where an asset row came from, for the provenance columns V11 requires. */
data class AssetProvenance(
    val sourceSystem: String,
    val actor: String,
    val correlationId: UUID,
)

/** What the asset endpoint reads. Null for an unknown id; tenant scoping is the caller's job. */
fun interface AssetStore {
    fun load(id: UUID): AssetRecord?
}
