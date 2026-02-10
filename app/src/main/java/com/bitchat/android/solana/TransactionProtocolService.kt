package com.bitchat.android.solana

import com.bitchat.android.data.models.SolanaTransactionPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encode/decode SolanaTransactionPacket for mesh (payload only; outer BitchatPacket is separate).
 * Format: version(1) + type(1) + senderLen(2) + senderUtf8 + recipientLen(2) + recipientUtf8 +
 *        amount(8) + timestamp(8) + flags(1) + txLen(4) + serializedTx + [signature(64)]
 */
object TransactionProtocolService {

    fun encode(packet: SolanaTransactionPacket): ByteArray {
        val senderBytes = packet.senderAddress.encodeToByteArray()
        val recipientBytes = packet.recipientAddress.encodeToByteArray()
        val sigLen = if (packet.signature != null) 64 else 0
        val cap = 1 + 1 + 2 + senderBytes.size + 2 + recipientBytes.size + 8 + 8 + 1 + 4 + packet.serializedTransaction.size + sigLen
        val buf = ByteBuffer.allocate(cap).order(ByteOrder.BIG_ENDIAN)
        buf.put(packet.version.toByte())
        buf.put(packet.transactionType.toByte())
        buf.putShort(senderBytes.size.toShort())
        buf.put(senderBytes)
        buf.putShort(recipientBytes.size.toShort())
        buf.put(recipientBytes)
        buf.putLong(packet.amountLamports)
        buf.putLong(packet.timestamp)
        buf.put(packet.flags.toByte())
        buf.putInt(packet.serializedTransaction.size)
        buf.put(packet.serializedTransaction)
        packet.signature?.let { buf.put(it) }
        return buf.array()
    }

    fun decode(payload: ByteArray): SolanaTransactionPacket? {
        if (payload.size < 1 + 1 + 2 + 2 + 8 + 8 + 1 + 4) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toUByte()
        val transactionType = buf.get().toUByte()
        val senderLen = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < senderLen) return null
        val senderBytes = ByteArray(senderLen)
        buf.get(senderBytes)
        val senderAddress = senderBytes.decodeToString()
        val recipientLen = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < recipientLen) return null
        val recipientBytes = ByteArray(recipientLen)
        buf.get(recipientBytes)
        val recipientAddress = recipientBytes.decodeToString()
        val amountLamports = buf.long
        val timestamp = buf.long
        val flags = buf.get().toUByte()
        val txLen = buf.int
        if (buf.remaining() < txLen) return null
        val serializedTransaction = ByteArray(txLen)
        buf.get(serializedTransaction)
        val signature = if (buf.remaining() >= 64) {
            val sig = ByteArray(64)
            buf.get(sig)
            sig
        } else null
        return SolanaTransactionPacket(
            version = version,
            transactionType = transactionType,
            serializedTransaction = serializedTransaction,
            senderAddress = senderAddress,
            recipientAddress = recipientAddress,
            amountLamports = amountLamports,
            timestamp = timestamp,
            flags = flags,
            signature = signature
        )
    }
}
