package dev.xshirae.neoplayersync

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement

/** Adds [millis] before every statement runs, like a database on another machine. */
internal fun withLatency(connection: Connection, millis: Long): Connection =
    proxy(connection) { result -> if (result is PreparedStatement) delayed(result, millis) else result }

private fun delayed(statement: PreparedStatement, millis: Long): PreparedStatement = proxy(statement, before = { method ->
    if (method.name.startsWith("execute")) Thread.sleep(millis)
}) { it }

private inline fun <reified T : Any> proxy(
    target: T,
    crossinline before: (Method) -> Unit = {},
    crossinline wrap: (Any?) -> Any?,
): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
    before(method)
    try {
        wrap(method.invoke(target, *(args ?: emptyArray())))
    } catch (exception: InvocationTargetException) {
        // The store must see the driver's SQLException, not the reflection wrapper.
        throw exception.targetException
    }
} as T
