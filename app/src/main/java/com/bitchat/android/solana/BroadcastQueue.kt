package com.bitchat.android.solana

import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.local.entities.QueuedTransactionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** TTL for queued transactions: 24 hours. */
private const val TTL_MS = 24 * 60 * 60 * 1000L

/**
 * Enqueue signed tx for delayed broadcast; dequeue when online (via BroadcastService).
 */
class BroadcastQueue(private val transactionDao: TransactionDao) {

    suspend fun enqueue(
        serializedTxBase64: String,
        recipientAddress: String? = null,
        amountLamports: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        val entity = QueuedTransactionEntity(
            serializedTxBase64 = serializedTxBase64,
            createdAtMillis = System.currentTimeMillis(),
            retryCount = 0,
            status = QueuedTransactionStatus.PENDING,
            recipientAddress = recipientAddress,
            amountLamports = amountLamports
        )
        transactionDao.insert(entity)
    }

    fun getPendingFlow(): Flow<List<QueuedTransactionEntity>> =
        transactionDao.getAllPending()

    suspend fun markSubmitted(id: Long) {
        transactionDao.updateStatus(id, QueuedTransactionStatus.SUBMITTED)
    }

    suspend fun markConfirmed(id: Long, signature: String) {
        transactionDao.updateStatus(id, QueuedTransactionStatus.CONFIRMED, signature = signature)
    }

    suspend fun markFailed(id: Long, error: String) {
        transactionDao.updateStatus(id, QueuedTransactionStatus.FAILED, lastError = error)
    }

    suspend fun incrementRetry(id: Long) {
        transactionDao.incrementRetry(id)
    }

    suspend fun delete(id: Long) {
        transactionDao.delete(id)
    }

    suspend fun getById(id: Long): QueuedTransactionEntity? = transactionDao.getById(id)

    fun isExpired(entity: QueuedTransactionEntity): Boolean =
        System.currentTimeMillis() - entity.createdAtMillis > TTL_MS
}
