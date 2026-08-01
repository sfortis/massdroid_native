package net.asksakis.massdroidv2.data.database

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The transaction boundary, as something a caller can hold rather than a
 * suspend extension on [AppDatabase].
 *
 * It exists so repository logic that has to be atomic can be unit-tested. This
 * module runs pure-JVM tests with no Robolectric, and `RoomDatabase.withTransaction`
 * is a suspend extension function: reaching it from a test means either mocking
 * a static or pulling in an instrumented database, and the first is brittle
 * while the second is a dependency the project deliberately does not carry.
 * A one-method interface costs less than either.
 */
interface TransactionRunner {
    suspend fun <R> inTransaction(block: suspend () -> R): R
}

@Singleton
class RoomTransactionRunner @Inject constructor(
    private val database: AppDatabase,
) : TransactionRunner {
    override suspend fun <R> inTransaction(block: suspend () -> R): R =
        database.withTransaction(block)
}
