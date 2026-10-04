package com.octo.api.asset.persistence

import com.octo.api.asset.Asset
import com.octo.api.asset.AssetProvenance
import com.octo.api.asset.AssetRecord
import com.octo.api.asset.AssetStore
import com.octo.api.asset.AssetType
import com.octo.api.asset.Identifier
import com.octo.iborcore.persistence.enqueueGraphUpsert
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** JDBC access to `octo.asset` and `octo.asset_xref` (V11). Insert and read only; both tables are append-only. */
class JdbcAssetStore(
    private val dataSource: DataSource,
) : AssetStore {
    /**
     * Stores [asset] and its new [identifiers] in one transaction. Identifiers of the rows it supersedes are inherited,
     * not copied. The same transaction enqueues the graph upsert for the asset's lineage (ADR-0004 amendment, #308), so
     * the row and its projection intent commit together.
     */
    fun create(
        asset: Asset,
        identifiers: List<Identifier>,
        provenance: AssetProvenance,
        scope: TenantScope,
    ) {
        val assetSql =
            """
            insert into octo.asset (id, tenant_id, asset_type, asset_class, display_name, region, tags, graph_node_id, supersedes_id,
                                     rationale, source_system, actor, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        dataSource.scoped(scope) { connection ->
            connection.prepareStatement(assetSql).use { statement ->
                statement.setObject(1, asset.id)
                statement.setObject(2, asset.tenantId)
                statement.setString(3, asset.type.wireValue)
                statement.setString(4, asset.assetClass)
                statement.setString(5, asset.displayName)
                statement.setString(6, asset.region)
                statement.setArray(7, connection.createArrayOf("text", asset.tags.toTypedArray()))
                statement.setString(8, asset.graphNodeId)
                statement.setObject(9, asset.supersedesId)
                statement.setString(10, asset.rationale)
                statement.setString(11, provenance.sourceSystem)
                statement.setString(12, provenance.actor)
                statement.setObject(13, provenance.correlationId)
                statement.executeUpdate()
            }
            connection.prepareStatement("insert into octo.asset_xref (asset_id, scheme, value) values (?, ?, ?)").use { statement ->
                for (identifier in identifiers) {
                    statement.setObject(1, asset.id)
                    statement.setString(2, identifier.scheme)
                    statement.setString(3, identifier.value)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            // The ontology key each asset type projects to (octo-investment.cypher): funds and operating companies are
            // keyed by legal name, investments by display name. A correction upserts the lineage's one node.
            val key = if (asset.type == AssetType.INVESTMENT) "displayName" else "legalName"
            enqueueGraphUpsert(
                connection,
                asset.tenantId,
                "asset",
                lineageRoot(connection, asset),
                mapOf("kind" to asset.type.wireValue, "properties" to mapOf(key to asset.displayName)),
            )
        }
    }

    /** The first row of [asset]'s lineage — the graph node's octoId, stable across every correction. */
    private fun lineageRoot(
        connection: Connection,
        asset: Asset,
    ): UUID {
        val supersedes = asset.supersedesId ?: return asset.id
        val sql =
            """
            with recursive lineage as (
                select id, supersedes_id from octo.asset where id = ?
                union
                select a.id, a.supersedes_id from octo.asset a join lineage l on a.id = l.supersedes_id)
            select id from lineage where supersedes_id is null
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, supersedes)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "asset ${asset.id} supersedes $supersedes, whose lineage has no root" }
                rows.getObject("id", UUID::class.java)
            }
        }
    }

    override fun load(
        id: UUID,
        scope: TenantScope,
    ): AssetRecord? =
        dataSource.scoped(scope) { connection ->
            val asset =
                connection.prepareStatement("select * from octo.asset where id = ?").use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) return null
                        AssetRecord(
                            asset =
                                Asset(
                                    id = id,
                                    tenantId = rows.getObject("tenant_id", UUID::class.java),
                                    type = AssetType.fromWireValue(rows.getString("asset_type")),
                                    assetClass = rows.getString("asset_class"),
                                    displayName = rows.getString("display_name"),
                                    region = rows.getString("region"),
                                    tags = (rows.getArray("tags").array as Array<*>).map { it as String },
                                    graphNodeId = rows.getString("graph_node_id"),
                                    supersedesId = rows.getObject("supersedes_id", UUID::class.java),
                                    rationale = rows.getString("rationale"),
                                ),
                            identifiers = emptyList(),
                            supersededBy = null,
                            recordedAt = rows.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                        )
                    }
                }
            asset.copy(identifiers = lineageIdentifiers(connection, id), supersededBy = supersededBy(connection, id))
        }

    /** Identifiers of this row and of every row it supersedes, in recording order. */
    private fun lineageIdentifiers(
        connection: Connection,
        id: UUID,
    ): List<Identifier> {
        val sql =
            """
            with recursive lineage as (
                select id, supersedes_id from octo.asset where id = ?
                union
                select a.id, a.supersedes_id from octo.asset a join lineage l on a.id = l.supersedes_id)
            select x.scheme, x.value from octo.asset_xref x join lineage l on x.asset_id = l.id order by x.recorded_at, x.scheme
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) Identifier(rows.getString("scheme"), rows.getString("value")) else null }.toList()
            }
        }
    }

    private fun supersededBy(
        connection: Connection,
        id: UUID,
    ): UUID? =
        connection.prepareStatement("select id from octo.asset where supersedes_id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("id", UUID::class.java) else null }
        }
}
