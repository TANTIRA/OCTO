package com.mesta.asset.api.asset.persistence

import com.mesta.asset.api.asset.Asset
import com.mesta.asset.api.asset.AssetProvenance
import com.mesta.asset.api.asset.AssetRecord
import com.mesta.asset.api.asset.AssetStore
import com.mesta.asset.api.asset.AssetType
import com.mesta.asset.api.asset.Identifier
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** JDBC access to `mesta.asset` and `mesta.asset_xref` (V11). Insert and read only; both tables are append-only. */
class JdbcAssetStore(
    private val dataSource: DataSource,
) : AssetStore {
    /** Stores [asset] and its new [identifiers] in one transaction. Identifiers of the rows it supersedes are inherited, not copied. */
    fun create(
        asset: Asset,
        identifiers: List<Identifier>,
        provenance: AssetProvenance,
    ) {
        val assetSql =
            """
            insert into mesta.asset (id, tenant_id, asset_type, asset_class, display_name, region, tags, typedb_iid, supersedes_id,
                                     rationale, source_system, actor, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(assetSql).use { statement ->
                    statement.setObject(1, asset.id)
                    statement.setObject(2, asset.tenantId)
                    statement.setString(3, asset.type.wireValue)
                    statement.setString(4, asset.assetClass)
                    statement.setString(5, asset.displayName)
                    statement.setString(6, asset.region)
                    statement.setArray(7, connection.createArrayOf("text", asset.tags.toTypedArray()))
                    statement.setString(8, asset.typedbIid)
                    statement.setObject(9, asset.supersedesId)
                    statement.setString(10, asset.rationale)
                    statement.setString(11, provenance.sourceSystem)
                    statement.setString(12, provenance.actor)
                    statement.setObject(13, provenance.correlationId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("insert into mesta.asset_xref (asset_id, scheme, value) values (?, ?, ?)").use { statement ->
                    for (identifier in identifiers) {
                        statement.setObject(1, asset.id)
                        statement.setString(2, identifier.scheme)
                        statement.setString(3, identifier.value)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    override fun load(id: UUID): AssetRecord? =
        dataSource.connection.use { connection ->
            val asset =
                connection.prepareStatement("select * from mesta.asset where id = ?").use { statement ->
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
                                    typedbIid = rows.getString("typedb_iid"),
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
                select id, supersedes_id from mesta.asset where id = ?
                union
                select a.id, a.supersedes_id from mesta.asset a join lineage l on a.id = l.supersedes_id)
            select x.scheme, x.value from mesta.asset_xref x join lineage l on x.asset_id = l.id order by x.recorded_at, x.scheme
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
        connection.prepareStatement("select id from mesta.asset where supersedes_id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("id", UUID::class.java) else null }
        }
}
