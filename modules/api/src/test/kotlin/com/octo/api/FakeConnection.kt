package com.octo.api

import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * The transaction an in-memory fake store hands to a task opener. It has no database behind it, so any use fails
 * loudly — openers under test record the task they are given and never touch the connection.
 */
val FAKE_CONNECTION: Connection =
    Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("fake connection has no database: ${method.name}")
    } as Connection
