package com.email.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The server's SQLite database. One connection, used by one coroutine at a time: a homelab
 * server has a handful of users, SQLite answers in microseconds, and a single writer can't hit
 * SQLite's lock errors.
 */
class Database(url: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection(url).apply {
        createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
            if (!url.contains(":memory:")) {
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = NORMAL")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Runs [block] in a transaction: everything in it is saved, or nothing is. */
    suspend fun <T> tx(block: Tx.() -> T): T = withContext(dispatcher) { blockingTx(block) }

    /** [tx] for start-up and command-line tools, before anything else uses the database. */
    fun <T> blockingTx(block: Tx.() -> T): T = synchronized(connection) {
        connection.autoCommit = false
        try {
            Tx(connection).block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    companion object {
        fun file(path: String) = Database("jdbc:sqlite:$path")
        fun inMemory() = Database("jdbc:sqlite::memory:")
    }
}

/** Statements inside a transaction. Arguments bind to `?` in order. */
class Tx(private val connection: Connection) {
    fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    fun update(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

    fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = prepare(sql, args).use { statement ->
        statement.executeQuery().use { results ->
            val row = Row(results)
            buildList { while (results.next()) add(map(row)) }
        }
    }

    fun <T> one(sql: String, vararg args: Any?, map: (Row) -> T): T? = query(sql, *args, map = map).firstOrNull()

    fun long(sql: String, vararg args: Any?): Long = one(sql, *args) { it.long(1) } ?: 0

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement = connection.prepareStatement(sql).apply {
        args.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> setNull(position, Types.NULL)
                is String -> setString(position, value)
                is Int -> setInt(position, value)
                is Long -> setLong(position, value)
                is Boolean -> setInt(position, if (value) 1 else 0)
                is Double -> setDouble(position, value)
                is ByteArray -> setBytes(position, value)
                is Enum<*> -> setString(position, value.name)
                else -> throw IllegalArgumentException("Can't store a ${value.javaClass.simpleName}")
            }
        }
    }
}

/** The current row of a query, read by column name or position. */
class Row(private val results: ResultSet) {
    fun string(column: String): String = results.getString(column)
    fun stringOrNull(column: String): String? = results.getString(column)
    fun long(column: String): Long = results.getLong(column)
    fun long(position: Int): Long = results.getLong(position)
    fun longOrNull(column: String): Long? = results.getLong(column).takeUnless { results.wasNull() }
    fun int(column: String): Int = results.getInt(column)
    fun intOrNull(column: String): Int? = results.getInt(column).takeUnless { results.wasNull() }
    fun bool(column: String): Boolean = results.getInt(column) != 0
}
