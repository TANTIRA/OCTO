package com.octo.persistence

/**
 * The per-request routing context for `TenantRoutingDataSource` (ADR-0007). When work must run
 * against a non-pool tenant's database, [within] binds the placement key to the calling thread;
 * every `getConnection()` the routing datasource serves while bound goes to that target, then the
 * binding unwinds. Nothing sets a context today — every tenant is `pool` — so the seam is dormant
 * until the first bridge tenant is provisioned (docs/tenant-isolation-runbook.md).
 */
object TenantRoutingContext {
    private val current = ThreadLocal<String>()

    /** The bound datasource key, or null when the request runs against the shared pool. */
    fun key(): String? = current.get()

    /**
     * Runs [block] with [datasourceKey] bound for the calling thread and restores the previous
     * binding afterwards — nested scopes are legal and unwind in order.
     */
    fun <T> within(
        datasourceKey: String,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(datasourceKey)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
