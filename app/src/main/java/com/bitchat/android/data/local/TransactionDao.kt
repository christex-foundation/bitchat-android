package com.bitchat.android.data.local

import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.local.entities.QueuedTransactionStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data access for queued Solana transactions.
 * MVP uses SharedPreferences-backed implementation; can be replaced by Room DAO later.
 */
interface TransactionDao {

    fun getAllPending(): Flow<List<QueuedTransactionEntity>>
    suspend fun insert(entity: QueuedTransactionEntity): Long
    suspend fun updateStatus(id: Long, status: QueuedTransactionStatus, signature: String? = null, lastError: String? = null)
    suspend fun incrementRetry(id: Long)
    suspend fun delete(id: Long)
    suspend fun deleteAll()
    suspend fun getById(id: Long): QueuedTransactionEntity?
}
