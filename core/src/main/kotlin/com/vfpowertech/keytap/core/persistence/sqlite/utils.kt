@file:JvmName("SQLiteUtils")
package com.vfpowertech.keytap.core.persistence.sqlite

import com.almworks.sqlite4java.*
import com.vfpowertech.keytap.core.loadSharedLibFromResource
import org.slf4j.LoggerFactory
import java.util.*

inline fun <R> SQLiteConnection.use(body: (SQLiteConnection) -> R): R =
    try {
        body(this)
    }
    finally {
        this.dispose()
    }

inline fun <R> SQLiteStatement.use(body: (SQLiteStatement) -> R): R =
    try {
        body(this)
    }
    finally {
        this.dispose()
    }

inline fun <R> SQLiteConnection.withTransaction(body: (SQLiteConnection) -> R): R {
    this.exec("BEGIN TRANSACTION")
    return try {
        val r = body(this)
        this.exec("COMMIT TRANSACTION")
        r
    }
    catch (e: Throwable) {
        this.exec("ROLLBACK TRANSACTION")
        throw e
    }
}

/**
 * @param binder Function that binds values of T to an SQLiteStatement
 */
fun <T> SQLiteConnection.batchInsert(sql: String, items: Iterable<T>, binder: (SQLiteStatement, T) -> Unit) {
    prepare(sql).use { stmt ->
        for (item in items) {
            binder(stmt, item)
            stmt.step()
            stmt.reset(true)
        }
    }
}

fun <T> SQLiteConnection.batchInsertWithinTransaction(sql: String, items: Iterable<T>, binder: (SQLiteStatement, T) -> Unit) =
    withTransaction { batchInsert(sql, items, binder) }

/** Escapes the given string for use with the LIKE operator. */
fun escapeLikeString(s: String, escape: Char): String =
    Regex("[%_$escape]").replace(s) { m ->
        "$escape${m.groups[0]!!.value}"
    }

fun isInvalidTableException(e: SQLiteException): Boolean {
    val message = e.message
    return if (message == null)
        false
    else
        e.baseErrorCode == SQLiteConstants.SQLITE_ERROR && "no such table:" in message
}

/** Calls the given function on all available query results. */
inline fun <T> SQLiteStatement.map(body: (SQLiteStatement) -> T): List<T> {
    val results = ArrayList<T>()

    while (step())
        results.add(body(this))

    return results
}

fun escapeBackticks(s: String) = s.replace("`", "``")

fun getPlaceholders(n: Int): String =
    "?".repeat(n).toList().joinToString(", ")

//not exposed; taken from Internal.getArch, getOS so we can unpack + load the shared lib from resources for the proper OS
private fun getArch(os: String): String {
    val arch = System.getProperty("os.arch").toLowerCase()
    return if (os == "win32" && arch == "amd64")
        "x64"
    else
        arch
}

private fun getOS(): String {
    val os = System.getProperty("os.name").toLowerCase()
    return when {
        os.startsWith("mac") || os.startsWith("darwin") || os.startsWith("os x") -> "osx"
        os.startsWith("windows") -> "win32"
        else -> {
            val runtimeName = System.getProperty("java.runtime.name")?.toLowerCase()
            if (runtimeName?.contains("android") == true)
                "android"
            else
                "linux"
        }
    }
}

fun sqlite4JavaGetLibraryName(): String {
    val os = getOS()
    val arch = getArch(os)

    return "sqlite4java-$os-$arch"
}

/** Loads the proper SQLite native library from the resource root. In core so it can be used by tests. */
fun Class<*>.loadSQLiteLibraryFromResources() {
    val base = sqlite4JavaGetLibraryName()
    LoggerFactory.getLogger(javaClass).info("Attempting to load SQLite native library: {}", base)
    loadSharedLibFromResource(base)
    SQLite.loadLibrary()
}