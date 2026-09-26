package com.mesta.asset.workflow.audit

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC access to `mesta.audit_event`. [append] is all the api's runtime role can do: V6 grants it INSERT only,
 * and the database assigns seq, recorded_at and the hashes. [readAll] needs a role that may read the audit log.
 */
class JdbcAuditLog(
    private val dataSource: DataSource,
) {
    fun append(entry: AuditEntry) {
        val sql =
            """
            insert into mesta.audit_event (occurred_at, actor, action, subject_type, subject_id, correlation_id, details)
            values (?, ?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, entry.occurredAt.atOffset(ZoneOffset.UTC))
                statement.setString(2, entry.actor)
                statement.setString(3, entry.action)
                statement.setString(4, entry.subjectType)
                statement.setString(5, entry.subjectId)
                statement.setObject(6, entry.correlationId)
                statement.setString(7, entry.details)
                statement.executeUpdate()
            }
        }
    }

    /** The whole chain in seq order, with details in the jsonb text form the database hashed. */
    fun readAll(): List<AuditRecord> {
        val sql =
            """
            select seq, occurred_at, recorded_at, actor, action, subject_type, subject_id, correlation_id,
                   details::text, prev_hash, hash
            from mesta.audit_event
            order by seq
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val entry =
                                AuditEntry(
                                    occurredAt = rows.getObject(2, OffsetDateTime::class.java).toInstant(),
                                    actor = rows.getString(4),
                                    action = rows.getString(5),
                                    subjectType = rows.getString(6),
                                    subjectId = rows.getString(7),
                                    correlationId = rows.getObject(8, UUID::class.java),
                                    details = rows.getString(9),
                                )
                            add(
                                AuditRecord(
                                    seq = rows.getLong(1),
                                    entry = entry,
                                    recordedAt = rows.getObject(3, OffsetDateTime::class.java).toInstant(),
                                    prevHash = rows.getBytes(10),
                                    hash = rows.getBytes(11),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
