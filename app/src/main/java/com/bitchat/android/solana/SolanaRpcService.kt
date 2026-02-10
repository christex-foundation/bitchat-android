package com.bitchat.android.solana

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Solana JSON-RPC client: getBalance, getLatestBlockhash, sendTransaction, getSignatureStatuses.
 */
class SolanaRpcService(private val rpcUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun getBalance(address: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val body = """{"jsonrpc":"2.0","id":1,"method":"getBalance","params":["$address"]}"""
            val response = post(body)
            val parsed = gson.fromJson(response, RpcBalanceResponse::class.java)
            if (parsed.error != null) return@withContext Result.failure(Exception(parsed.error.message))
            Result.success(parsed.result?.value ?: 0L)
        } catch (e: Exception) {
            Log.e(TAG, "getBalance failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getLatestBlockhash(): Result<BlockhashResult> = withContext(Dispatchers.IO) {
        try {
            val body = """{"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"finalized"}]}"""
            val response = post(body)
            val parsed = gson.fromJson(response, RpcBlockhashResponse::class.java)
            if (parsed.error != null) return@withContext Result.failure(Exception(parsed.error.message))
            val bh = parsed.result?.value ?: return@withContext Result.failure(Exception("No blockhash in response"))
            Result.success(BlockhashResult(bh.blockhash, bh.lastValidBlockHeight))
        } catch (e: Exception) {
            Log.e(TAG, "getLatestBlockhash failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun sendTransaction(serializedTxBase64: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val params = """["$serializedTxBase64",{"encoding":"base64","skipPreflight":false}]"""
            val body = """{"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":$params}"""
            val response = post(body)
            val parsed = gson.fromJson(response, RpcStringResponse::class.java)
            if (parsed.error != null) return@withContext Result.failure(Exception(parsed.error.message ?: "RPC error"))
            Result.success(parsed.result ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "sendTransaction failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getSignatureStatuses(signatures: List<String>): Result<List<SignatureStatus?>> = withContext(Dispatchers.IO) {
        try {
            val params = gson.toJson(signatures)
            val body = """{"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[$params]}"""
            val response = post(body)
            val parsed = gson.fromJson(response, RpcSignatureStatusesResponse::class.java)
            if (parsed.error != null) return@withContext Result.failure(Exception(parsed.error.message))
            Result.success(parsed.result?.value ?: emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "getSignatureStatuses failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun post(jsonBody: String): String {
        val request = Request.Builder()
            .url(rpcUrl)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
        return response.body?.string() ?: throw Exception("Empty response body")
    }

    data class BlockhashResult(val blockhash: String, val lastValidBlockHeight: Long)

    data class SignatureStatus(
        @SerializedName("confirmationStatus") val confirmationStatus: String?,
        @SerializedName("confirmations") val confirmations: Long?,
        @SerializedName("err") val err: Any?
    )

    private data class RpcError(val message: String?)
    private data class RpcBalanceResponse(val result: BalanceResult?, val error: RpcError?)
    private data class BalanceResult(val value: Long)
    private data class RpcBlockhashResponse(val result: BlockhashValueWrapper?, val error: RpcError?)
    private data class BlockhashValueWrapper(val value: BlockhashValue)
    private data class BlockhashValue(
        @SerializedName("blockhash") val blockhash: String,
        @SerializedName("lastValidBlockHeight") val lastValidBlockHeight: Long
    )
    private data class RpcStringResponse(val result: String?, val error: RpcError?)
    private data class RpcSignatureStatusesResponse(val result: SignatureStatusesValue?, val error: RpcError?)
    private data class SignatureStatusesValue(val value: List<SignatureStatus?>)

    companion object {
        private const val TAG = "SolanaRpcService"
    }
}
