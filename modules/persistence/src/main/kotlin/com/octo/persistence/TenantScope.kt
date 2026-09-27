package com.octo.persistence

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * The tenant boundary one store call executes under (#197). `scoped` mirrors it into the
 * transaction-local GUCs `app.user_id` / `app.tenant_ids`.
 *
 * The row-level-security policies that read those GUCs land in the RLS slice of #197 — until they
 * exist, isolation comes from each store query's own `tenant_id` predicates plus the membership
 * checks at the API edge, exactly as before. Once the policies ship, a call that forgets to scope
 * fails closed (the policies see no user and no tenants), never open.
 *
 * Service scopes are not a privilege escalation: the caller is already trusted code; the
 * boundary protects against the *omitted* `tenant_id` filter, not against the caller itself.
 */
sealed interface TenantScope {
    /** A request acting as one user; policies check that user's membership in the row's tenant. */
    data class User(
        val userId: UUID,
    ) : TenantScope

    /** A service acting for the listed tenants — a run that knows its tenant set up front. */
    data class Tenants(
        val tenantIds: List<UUID>,
    ) : TenantScope

    /** A platform-wide scan (collectors/pollers that legitimately span tenants). */
    data object All : TenantScope
}

/**
 * Opens a connection in one transaction with [scope]'s GUCs set (`set_config(…, is_local = true)`
 * dies with the transaction, so a pooled connection can never carry scope to the next borrower)
 * and commits or rolls back around [block].
 */
inline fun <T> DataSource.scoped(
    scope: TenantScope,
    block: (Connection) -> T,
): T =
    connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.applyTenantScope(scope)
            val result = block(connection)
            connection.commit()
            result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        }
    }

@PublishedApi
internal fun Connection.applyTenantScope(scope: TenantScope) {
    prepareStatement("select set_config('app.user_id', ?, true), set_config('app.tenant_ids', ?, true)").use { statement ->
        when (scope) {
            is TenantScope.User -> {
                statement.setString(1, scope.userId.toString())
                statement.setString(2, null)
            }
            is TenantScope.Tenants -> {
                statement.setString(1, null)
                statement.setString(2, scope.tenantIds.joinToString(","))
            }
            TenantScope.All -> {
                statement.setString(1, null)
                statement.setString(2, "*")
            }
        }
        statement.execute()
    }
}
