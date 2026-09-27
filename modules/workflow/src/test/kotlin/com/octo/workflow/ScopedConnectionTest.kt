package com.octo.workflow

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * `DataSource.scoped` is the seam the RLS policies hang off (#197): these tests pin the contract
 * — the GUCs the connection sees, the transaction boundaries, and that a thrown block rolls back
 * instead of leaking a half-open scope to the pool.
 */
class ScopedConnectionTest {
    @Test
    fun `a user scope sets user_id, blanks tenant_ids, commits and closes`() {
        val jdbc = FakeJdbc()
        val userId = UUID.randomUUID()
        val result = jdbc.dataSource.scoped(TenantScope.User(userId)) { "ok" }

        assertEquals("ok", result)
        assertEquals("select set_config('app.user_id', ?, true), set_config('app.tenant_ids', ?, true)", jdbc.statementSql.single())
        assertEquals(userId.toString(), jdbc.bound[1])
        assertEquals(null, jdbc.bound[2])
        assertEquals(listOf("setAutoCommit:false", "commit", "close"), jdbc.lifecycle)
    }

    @Test
    fun `a tenants scope carries the id list and an all scope carries the platform marker`() {
        val jdbc = FakeJdbc()
        val tenants = listOf(UUID.randomUUID(), UUID.randomUUID())
        jdbc.dataSource.scoped(TenantScope.Tenants(tenants)) {}
        assertEquals(null, jdbc.bound[1])
        assertEquals(tenants.joinToString(","), jdbc.bound[2])

        val jdbc2 = FakeJdbc()
        jdbc2.dataSource.scoped(TenantScope.All) {}
        assertEquals("*", jdbc2.bound[2])
    }

    @Test
    fun `a throwing block rolls back and the failure propagates`() {
        val jdbc = FakeJdbc()
        val boom = IllegalStateException("boom")
        val thrown =
            assertFailsWith<IllegalStateException> {
                jdbc.dataSource.scoped(TenantScope.All) { throw boom }
            }
        assertIs<IllegalStateException>(thrown)
        assertEquals(listOf("setAutoCommit:false", "rollback", "close"), jdbc.lifecycle)
    }

    /** A minimal JDBC stack: the statement records its SQL and bound params, the connection its lifecycle calls. */
    private class FakeJdbc {
        val bound = mutableMapOf<Int, String?>()
        val statementSql = mutableListOf<String>()
        val lifecycle = mutableListOf<String>()
        private var closed = false
        private var autoCommit = true

        private val statement: PreparedStatement =
            proxy(PreparedStatement::class.java) { method, args ->
                when (method.name) {
                    "setString" -> bound[args[0] as Int] = args[1] as String?
                    "execute" -> true
                    "close" -> null
                    else -> defaultValue(method)
                }
            }

        private val connection: Connection =
            proxy(Connection::class.java) { method, args ->
                when (method.name) {
                    "prepareStatement" -> {
                        statementSql += args[0] as String
                        statement
                    }
                    "setAutoCommit" -> {
                        autoCommit = args[0] as Boolean
                        lifecycle += "setAutoCommit:$autoCommit"
                        null
                    }
                    "getAutoCommit" -> autoCommit
                    "commit" -> {
                        lifecycle += "commit"
                        null
                    }
                    "rollback" -> {
                        lifecycle += "rollback"
                        null
                    }
                    "close" -> {
                        closed = true
                        lifecycle += "close"
                        null
                    }
                    "isClosed" -> closed
                    else -> defaultValue(method)
                }
            }

        val dataSource: DataSource =
            proxy(DataSource::class.java) { method, _ ->
                when (method.name) {
                    "getConnection" -> connection
                    else -> defaultValue(method)
                }
            }

        private fun <T> proxy(
            type: Class<T>,
            handle: (Method, Array<out Any?>) -> Any?,
        ): T {
            val handler = InvocationHandler { _, method, args -> handle(method, args ?: emptyArray()) }
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
        }

        private fun defaultValue(method: Method): Any? =
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
    }
}
