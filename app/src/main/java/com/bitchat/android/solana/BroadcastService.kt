package com.bitchat.android.solana

import android.util.Log
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.local.entities.QueuedTransactionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * When online: processes BroadcastQueue, submits txs via RPC, refreshes blockhash if needed,
 * polls for confirmation; exponential backoff on failure.
 */
class BroadcastService(
    private val rpc: SolanaRpcService,
    private val queue: BroadcastQueue,
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var processJob: Job? = null

    fun start() {
        processJob?.cancel()
        processJob = scope.launch {
            while (isActive) {
                val online = networkMonitor.isOnline.first()
                if (online) processQueue()
                delay(5_000)
            }
        }
    }

    fun stop() {
        processJob?.cancel()
        processJob = null
    }

    private suspend fun processQueue() {
        while (scope.isActive) {
            try {
                val pending = queue.getPendingFlow().first()
                val valid = pending.filter { !queue.isExpired(it) }
                for (entity in valid) {
                    if (entity.status != QueuedTransactionStatus.PENDING) continue
                    processOne(entity)
                }
            } catch (_: Exception) { }
            delay(5_000)
        }
    }

    private suspend fun processOne(entity: QueuedTransactionEntity) = withContext(Dispatchers.IO) {
        try {
            queue.markSubmitted(entity.id)
            val result = rpc.sendTransaction(entity.serializedTxBase64)
            result.fold(
                onSuccess = { signature ->
                    pollConfirmation(signature, entity.id)
                },
                onFailure = { e ->
                    Log.e(TAG, "sendTransaction failed: ${e.message}")
                    queue.markFailed(entity.id, e.message ?: "Send failed")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "processOne failed: ${e.message}")
            queue.incrementRetry(entity.id)
        }
    }

    private suspend fun pollConfirmation(signature: String, entityId: Long) {
        var backoff = 1000L
        repeat(30) {
            delay(backoff)
            val result = rpc.getSignatureStatuses(listOf(signature))
            result.getOrNull()?.firstOrNull()?.let { status ->
                if (status.err != null) {
                    queue.markFailed(entityId, status.err.toString())
                    return
                }
                if (status.confirmationStatus == "finalized" || status.confirmationStatus == "confirmed") {
                    queue.markConfirmed(entityId, signature)
                    return
                }
            }
            backoff = (backoff * 1.5).toLong().coerceAtMost(10_000)
        }
        queue.markFailed(entityId, "Confirmation timeout")
    }

    companion object {
        private const val TAG = "BroadcastService"
    }
}
