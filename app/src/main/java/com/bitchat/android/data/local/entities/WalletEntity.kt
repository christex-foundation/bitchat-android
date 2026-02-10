package com.bitchat.android.data.local.entities

/**
 * Wallet entity for persistence (e.g. Room).
 * MVP uses EncryptedSharedPreferences in SolanaWalletService; this schema supports future multi-wallet.
 */
data class WalletEntity(
    val id: Long = 0,
    val publicKeyBase58: String,
    val createdAtMillis: Long,
    val isDefault: Boolean = true
)
