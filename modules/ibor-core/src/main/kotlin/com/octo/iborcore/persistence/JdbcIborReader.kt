package com.mesta.asset.iborcore.persistence

import com.mesta.asset.iborcore.FlowType
import com.mesta.asset.iborcore.LedgerEvent
import com.mesta.asset.iborcore.ValuationEvent
import com.mesta.asset.iborcore.ValuationMethod
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Currency
import java.util.UUID
import javax.sql.DataSource

/**
 * Reads the append-only IBOR fact tables. Each call returns the supersession component of [members]
 * (up to the originals, then down to every correction): what derivation needs, not the whole table.
 */
class JdbcIborReader(
    private val dataSource: DataSource,
) {
    fun ledgerEvents(members: Set<UUID>): List<LedgerEvent> =
        component(LEDGER, members, "flow_type, occurred_at") { row ->
            LedgerEvent(
                id = row.uuid("id")!!,
                flowType = FlowType.entries.first { it.wireValue == row.getString("flow_type") },
                amount = row.getBigDecimal("monetary_amount"),
                currency = Currency.getInstance(row.getString("currency_code")),
                occurredAt = row.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                recordedAt = row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                supersedesId = row.uuid("supersedes_id"),
            )
        }

    fun valuationEvents(members: Set<UUID>): List<ValuationEvent> =
        component(VALUATION, members, "as_of_date, valuation_method") { row ->
            ValuationEvent(
                id = row.uuid("id")!!,
                amount = row.getBigDecimal("monetary_amount"),
                currency = Currency.getInstance(row.getString("currency_code")),
                asOfDate = row.getObject("as_of_date", LocalDate::class.java),
                method = row.getString("valuation_method")?.let(ValuationMethod::fromWireValue),
                recordedAt = row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                supersedesId = row.uuid("supersedes_id"),
            )
        }

    private fun <T> component(
        table: String,
        members: Set<UUID>,
        extraColumns: String,
        map: (ResultSet) -> T,
    ): List<T> {
        if (members.isEmpty()) return emptyList()
        // table and extraColumns are the constants above, never caller input.
        val sql =
            """
            with recursive up as (
                select id, supersedes_id from $table where id = any(?)
                union
                select t.id, t.supersedes_id from $table t join up on t.id = up.supersedes_id
            ),
            down as (
                select id from up where supersedes_id is null
                union
                select t.id from $table t join down on t.supersedes_id = down.id
            )
            select id, monetary_amount, currency_code, recorded_at, supersedes_id, $extraColumns
            from $table
            where id in (select id from down)
            order by recorded_at, id
            """.trimIndent()
        dataSource.connection.use { connection ->
            val ids = connection.createArrayOf("uuid", members.toTypedArray())
            connection.prepareStatement(sql).use { statement ->
                statement.setArray(1, ids)
                statement.executeQuery().use { rows ->
                    return buildList { while (rows.next()) add(map(rows)) }
                }
            }
        }
    }

    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

    private companion object {
        const val LEDGER = "mesta.ledger_event"
        const val VALUATION = "mesta.valuation_event"
    }
}
