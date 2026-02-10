package com.bitchat.android.solana

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Solana wallet: generate keypair (Ed25519), store secret and public key in EncryptedSharedPreferences.
 * No Room for MVP (avoids Kotlin 2.2 + Room compiler issue).
 */
class SolanaWalletService(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun hasWallet(): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(KEY_PRIVATE_KEY)
    }

    suspend fun getPublicKeyBase58(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_PUBLIC_KEY_BASE58, null)
    }

    suspend fun getPublicKeyBytes(): ByteArray? {
        val b58 = getPublicKeyBase58() ?: return null
        return try {
            Base58.decode(b58)
        } catch (_: Exception) {
            null
        }
    }

    /** Create a new wallet and persist it. */
    suspend fun createWallet(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (privateKey, publicKey) = generateEd25519KeyPair()
            val publicKeyBase58 = Base58.encode(publicKey)
            prefs.edit()
                .putString(KEY_PRIVATE_KEY, java.util.Base64.getEncoder().encodeToString(privateKey))
                .putString(KEY_PUBLIC_KEY_BASE58, publicKeyBase58)
                .apply()
            Result.success(publicKeyBase58)
        } catch (e: Exception) {
            Log.e(TAG, "createWallet failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Sign payload with current wallet's private key. Returns 64-byte signature or null. */
    fun sign(data: ByteArray): ByteArray? {
        val privateKeyB64 = prefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val privateKey = try {
            java.util.Base64.getDecoder().decode(privateKeyB64)
        } catch (_: Exception) {
            return null
        }
        if (privateKey.size < 32) return null
        return signWithEd25519(data, privateKey)
    }

    /** Export seed/private key for backup (MVP: export raw private key base64). */
    fun exportPrivateKeyBase64(): String? = prefs.getString(KEY_PRIVATE_KEY, null)

    /** Import wallet from existing private key (32 bytes). Returns public key base58. */
    suspend fun importWallet(privateKey: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        if (privateKey.size < 32) return@withContext Result.failure(IllegalArgumentException("Invalid key length"))
        try {
            val publicKey = getPublicKeyFromPrivate(privateKey)
            val publicKeyBase58 = Base58.encode(publicKey)
            prefs.edit()
                .putString(KEY_PRIVATE_KEY, java.util.Base64.getEncoder().encodeToString(privateKey.take(32).toByteArray()))
                .putString(KEY_PUBLIC_KEY_BASE58, publicKeyBase58)
                .apply()
            Result.success(publicKeyBase58)
        } catch (e: Exception) {
            Log.e(TAG, "importWallet failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val keyGen = Ed25519KeyPairGenerator()
        keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyGen.generateKeyPair()
        val privateKey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        return Pair(privateKey, publicKey)
    }

    private fun getPublicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        val params = Ed25519PrivateKeyParameters(privateKey.take(32).toByteArray(), 0)
        return params.generatePublicKey().encoded
    }

    private fun signWithEd25519(data: ByteArray, privateKey: ByteArray): ByteArray {
        val params = Ed25519PrivateKeyParameters(privateKey.take(32).toByteArray(), 0)
        val signer = Ed25519Signer()
        signer.init(true, params)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    companion object {
        private const val TAG = "SolanaWalletService"
        private const val PREFS_NAME = "solana_wallet_prefs"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_PUBLIC_KEY_BASE58 = "public_key_base58"
    }
}
