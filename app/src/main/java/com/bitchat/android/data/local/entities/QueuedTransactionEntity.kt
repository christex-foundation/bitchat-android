package com.bitchat.android.data.local.entities

/**
 * Queued Solana transaction for delayed broadcast.
 * Persisted so transactions survive app restart; TTL and retry handled by BroadcastQueue.
 */
data class QueuedTransactionEntity(
    val id: Long = 0,
    val serializedTxBase64: String,
    val createdAtMillis: Long,
    val retryCount: Int = 0,
    val status: QueuedTransactionStatus = QueuedTransactionStatus.PENDING,
    val recipientAddress: String? = null,
    val amountLamports: Long? = null,
    val lastError: String? = null,
    val signature: String? = null
)

enum class QueuedTransactionStatus {
    PENDING,
    SUBMITTED,
    CONFIRMED,
    FAILED,
    EXPIRED
}
