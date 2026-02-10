package com.bitchat.android.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.BitchatApplication
import com.bitchat.android.solana.Base58
import com.bitchat.android.solana.SolanaRpcService
import com.bitchat.android.solana.SolanaWalletService
import com.bitchat.android.solana.TransactionBuilder
import kotlinx.coroutines.launch

/**
 * Result of creating and signing a transfer (for hand-off to protocol/broadcast).
 */
data class SignedTransferResult(
    val serializedTxBase64: String,
    val recipientAddress: String,
    val amountLamports: Long
)

/**
 * ViewModel for sending SOL: build tx, sign, hand off to queue/protocol.
 */
class SendTransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BitchatApplication
    private val rpc: SolanaRpcService get() = app.solanaRpcService
    private val wallet: SolanaWalletService get() = app.solanaWalletService

    private val _sendState = MutableLiveData<SendState>(SendState.Idle)
    val sendState: LiveData<SendState> = _sendState

    private val _recipient = MutableLiveData("")
    val recipient: LiveData<String> = _recipient

    /** Set recipient address (e.g. from peer selection). */
    fun setRecipient(address: String) {
        _recipient.value = address
    }

    sealed class SendState {
        object Idle : SendState()
        object LoadingBlockhash : SendState()
        data class ReadyToConfirm(val recipient: String, val amountLamports: Long, val priorityFee: Boolean) : SendState()
        object Signing : SendState()
        data class Success(val result: SignedTransferResult) : SendState()
        data class Error(val message: String) : SendState()
    }

    fun prepareSend(recipientAddress: String, amountLamports: Long, priorityFee: Boolean) {
        val addr = recipientAddress.trim()
        if (addr.isEmpty()) {
            _sendState.value = SendState.Error("Recipient required")
            return
        }
        if (amountLamports <= 0) {
            _sendState.value = SendState.Error("Amount must be positive")
            return
        }
        val recipientBytes = try {
            Base58.decode(addr)
        } catch (_: Exception) {
            _sendState.value = SendState.Error("Invalid Solana address")
            return
        }
        if (recipientBytes.size != 32) {
            _sendState.value = SendState.Error("Invalid Solana address length")
            return
        }
        _sendState.value = SendState.ReadyToConfirm(addr, amountLamports, priorityFee)
    }

    fun confirmAndSign() {
        val state = _sendState.value as? SendState.ReadyToConfirm ?: return
        viewModelScope.launch {
            _sendState.value = SendState.Signing
            val blockhashResult = rpc.getLatestBlockhash()
            val blockhash = blockhashResult.getOrNull() ?: run {
                _sendState.value = SendState.Error("Failed to get blockhash")
                return@launch
            }
            val feePayerBytes = wallet.getPublicKeyBytes() ?: run {
                _sendState.value = SendState.Error("No wallet")
                return@launch
            }
            val recipientBytes = try {
                Base58.decode(state.recipient)
            } catch (_: Exception) {
                _sendState.value = SendState.Error("Invalid recipient address")
                return@launch
            }
            val blockhashBytes = try {
                Base58.decode(blockhash.blockhash)
            } catch (_: Exception) {
                _sendState.value = SendState.Error("Invalid blockhash")
                return@launch
            }
            if (blockhashBytes.size != 32) {
                _sendState.value = SendState.Error("Invalid blockhash length")
                return@launch
            }
            val messageBytes = TransactionBuilder.buildTransferMessage(
                blockhash = blockhashBytes,
                feePayerPubkey = feePayerBytes,
                recipientPubkey = recipientBytes,
                lamports = state.amountLamports
            )
            val signature = wallet.sign(messageBytes) ?: run {
                _sendState.value = SendState.Error("Signing failed")
                return@launch
            }
            val signedTxBytes = TransactionBuilder.buildSignedTransaction(messageBytes, signature)
            val base64 = android.util.Base64.encodeToString(signedTxBytes, android.util.Base64.NO_WRAP)
            app.broadcastQueue.enqueue(
                serializedTxBase64 = base64,
                recipientAddress = state.recipient,
                amountLamports = state.amountLamports
            )
            _sendState.postValue(
                SendState.Success(
                    SignedTransferResult(
                        serializedTxBase64 = base64,
                        recipientAddress = state.recipient,
                        amountLamports = state.amountLamports
                    )
                )
            )
        }
    }

    fun resetState() {
        _sendState.value = SendState.Idle
    }
}
