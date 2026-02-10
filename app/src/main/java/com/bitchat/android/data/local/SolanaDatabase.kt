package com.bitchat.android.data.local

import android.content.Context
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.local.entities.QueuedTransactionStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory + SharedPreferences backed store for queued transactions.
 * Schema matches Room entities; can be replaced by Room when compiler is enabled.
 */
class SolanaDatabase(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val typeToken = object : TypeToken<List<QueuedTransactionEntity>>() {}
    private val mutex = Mutex()
    private val idGenerator = AtomicLong(1)

    fun transactionDao(): TransactionDao = object : TransactionDao {
        override fun getAllPending(): Flow<List<QueuedTransactionEntity>> = flow {
            mutex.withLock {
                val list = loadAll().filter { it.status == QueuedTransactionStatus.PENDING }
                emit(list)
            }
        }

        override suspend fun insert(entity: QueuedTransactionEntity): Long = mutex.withLock {
            val id = idGenerator.getAndIncrement()
            val newEntity = entity.copy(id = id)
            val list = loadAll().toMutableList()
            list.add(newEntity)
            saveAll(list)
            id
        }

        override suspend fun updateStatus(id: Long, status: QueuedTransactionStatus, signature: String?, lastError: String?) = mutex.withLock {
            val list = loadAll().map {
                if (it.id == id) it.copy(status = status, signature = signature ?: it.signature, lastError = lastError ?: it.lastError)
                else it
            }
            saveAll(list)
        }

        override suspend fun incrementRetry(id: Long) = mutex.withLock {
            val list = loadAll().map {
                if (it.id == id) it.copy(retryCount = it.retryCount + 1)
                else it
            }
            saveAll(list)
        }

        override suspend fun delete(id: Long) = mutex.withLock {
            saveAll(loadAll().filter { it.id != id })
        }

        override suspend fun deleteAll() = mutex.withLock {
            saveAll(emptyList())
        }

        override suspend fun getById(id: Long): QueuedTransactionEntity? = mutex.withLock {
            loadAll().find { it.id == id }
        }
    }

    private fun loadAll(): List<QueuedTransactionEntity> {
        val json = prefs.getString(KEY_QUEUED, null) ?: return emptyList()
        return try {
            gson.fromJson(json, typeToken.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(list: List<QueuedTransactionEntity>) {
        prefs.edit().putString(KEY_QUEUED, gson.toJson(list)).apply()
        val maxId = list.maxOfOrNull { it.id } ?: 0L
        if (maxId >= idGenerator.get()) idGenerator.set(maxId + 1)
    }

    companion object {
        private const val PREFS_NAME = "solana_db"
        private const val KEY_QUEUED = "queued_transactions"
    }
}
