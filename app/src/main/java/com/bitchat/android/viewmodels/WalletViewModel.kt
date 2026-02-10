package com.bitchat.android.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.BitchatApplication
import com.bitchat.android.solana.SolanaRpcService
import com.bitchat.android.solana.SolanaWalletService
import kotlinx.coroutines.launch

/**
 * Wallet state for UI.
 */
sealed class WalletState {
    object NoWallet : WalletState()
    data class Ready(val address: String) : WalletState()
    object Loading : WalletState()
    data class Error(val message: String) : WalletState()
}

/**
 * ViewModel for Solana wallet: balance, address, create/import, refresh, export seed.
 */
class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BitchatApplication
    private val rpc: SolanaRpcService get() = app.solanaRpcService
    private val wallet: SolanaWalletService get() = app.solanaWalletService

    private val _walletState = MutableLiveData<WalletState>(WalletState.Loading)
    val walletState: LiveData<WalletState> = _walletState

    private val _balanceLamports = MutableLiveData<Long?>(null)
    val balanceLamports: LiveData<Long?> = _balanceLamports

    private val _exportSeedResult = MutableLiveData<Result<String>?>(null)
    val exportSeedResult: LiveData<Result<String>?> = _exportSeedResult

    private val _toastMessage = MutableLiveData<String?>(null)
    val toastMessage: LiveData<String?> = _toastMessage

    init {
        loadWalletState()
    }

    fun loadWalletState() {
        viewModelScope.launch {
            _walletState.postValue(WalletState.Loading)
            val hasWallet = wallet.hasWallet()
            if (!hasWallet) {
                _walletState.postValue(WalletState.NoWallet)
                return@launch
            }
            val address = wallet.getPublicKeyBase58()
            if (address == null) {
                _walletState.postValue(WalletState.NoWallet)
                return@launch
            }
            _walletState.postValue(WalletState.Ready(address))
            refreshBalance()
        }
    }

    fun createWallet() {
        viewModelScope.launch {
            _walletState.postValue(WalletState.Loading)
            val result = wallet.createWallet()
            result.fold(
                onSuccess = { address ->
                    _walletState.postValue(WalletState.Ready(address))
                    refreshBalance()
                },
                onFailure = {
                    _walletState.postValue(WalletState.Error(it.message ?: "Failed to create wallet"))
                }
            )
        }
    }

    fun importWallet(privateKeyBase64: String) {
        viewModelScope.launch {
            val bytes = try {
                android.util.Base64.decode(privateKeyBase64, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                _walletState.postValue(WalletState.Error("Invalid key format"))
                return@launch
            }
            _walletState.postValue(WalletState.Loading)
            val result = wallet.importWallet(bytes)
            result.fold(
                onSuccess = { address ->
                    _walletState.postValue(WalletState.Ready(address))
                    refreshBalance()
                },
                onFailure = {
                    _walletState.postValue(WalletState.Error(it.message ?: "Failed to import wallet"))
                }
            )
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            val address = wallet.getPublicKeyBase58() ?: return@launch
            val result = rpc.getBalance(address)
            result.fold(
                onSuccess = { _balanceLamports.postValue(it) },
                onFailure = { _balanceLamports.postValue(null) }
            )
        }
    }

    fun exportSeedPhrase() {
        viewModelScope.launch {
            val key = wallet.exportPrivateKeyBase64()
            if (key != null) {
                _exportSeedResult.postValue(Result.success(key))
            } else {
                _exportSeedResult.postValue(Result.failure(Exception("No wallet")))
            }
        }
    }

    fun clearExportResult() {
        _exportSeedResult.value = null
    }

    fun showToast(message: String) {
        _toastMessage.value = message
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}
