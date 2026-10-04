package com.octo.iborcore.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.util.UUID

private val json = ObjectMapper()

/**
 * Enqueues one graph upsert on the caller's [connection] — the transaction that writes the domain row — so the row
 * and the intent to project it commit or roll back together (ADR-0004 amendment, #308). [payload] is the full
 * node state (`kind`, `properties`, and for a relationship the `endpoints` map). The projector derives labels and
 * relationship types from [aggregateType] and `kind`, never from caller text.
 */
fun enqueueGraphUpsert(
    connection: Connection,
    tenantId: UUID,
    aggregateType: String,
    aggregateId: UUID,
    payload: Map<String, Any?>,
) {
    connection
        .prepareStatement(
            "insert into octo.graph_outbox (tenant_id, aggregate_type, aggregate_id, op, payload) values (?, ?, ?, 'upsert', ?::jsonb)",
        ).use { statement ->
            statement.setObject(1, tenantId)
            statement.setString(2, aggregateType)
            statement.setObject(3, aggregateId)
            statement.setString(4, json.writeValueAsString(payload))
            statement.executeUpdate()
        }
}
