package com.bitchat.android.data.models

/**
 * Solana transaction packet for mesh transfer.
 * Fields: version, transactionType, serializedTransaction, senderAddress, recipientAddress,
 * amount (lamports), timestamp, flags, signature (64 bytes).
 */
data class SolanaTransactionPacket(
    val version: UByte = 1u,
    val transactionType: UByte,
    val serializedTransaction: ByteArray,
    val senderAddress: String,
    val recipientAddress: String,
    val amountLamports: Long,
    val timestamp: Long,
    val flags: UByte = 0u,
    val signature: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SolanaTransactionPacket
        if (version != other.version) return false
        if (transactionType != other.transactionType) return false
        if (!serializedTransaction.contentEquals(other.serializedTransaction)) return false
        if (senderAddress != other.senderAddress) return false
        if (recipientAddress != other.recipientAddress) return false
        if (amountLamports != other.amountLamports) return false
        if (timestamp != other.timestamp) return false
        if (flags != other.flags) return false
        if (signature != null) {
            if (other.signature == null || !signature.contentEquals(other.signature)) return false
        } else if (other.signature != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + transactionType.hashCode()
        result = 31 * result + serializedTransaction.contentHashCode()
        result = 31 * result + senderAddress.hashCode()
        result = 31 * result + recipientAddress.hashCode()
        result = 31 * result + amountLamports.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + flags.hashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}
