package com.bitchat.android.solana

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a legacy Solana SOL transfer transaction and serializes to bytes.
 * Wire format: compact-u16 lengths, 32-byte pubkeys, 64-byte signatures.
 */
object TransactionBuilder {

    // System Program: 11111111111111111111111111111111 (base58) = 32 zero bytes in some SDKs; Solana uses 32-byte pubkey.
    private val SYSTEM_PROGRAM_ID = ByteArray(32) { 0 }
    private const val TRANSFER_INSTRUCTION_INDEX = 2

    /**
     * Build unsigned message bytes for a SOL transfer (message only, no signatures).
     * Caller signs this with the fee payer key; then use buildSignedTransaction.
     *
     * @param blockhash 32-byte recent blockhash
     * @param feePayerPubkey 32-byte sender/fee payer public key
     * @param recipientPubkey 32-byte recipient public key
     * @param lamports amount in lamports
     */
    fun buildTransferMessage(
        blockhash: ByteArray,
        feePayerPubkey: ByteArray,
        recipientPubkey: ByteArray,
        lamports: Long
    ): ByteArray {
        require(blockhash.size == 32)
        require(feePayerPubkey.size == 32)
        require(recipientPubkey.size == 32)
        // Accounts: 0 = fee payer (signer, writable), 1 = recipient (writable), 2 = system program (readonly)
        val accountKeys = listOf(feePayerPubkey, recipientPubkey, SYSTEM_PROGRAM_ID)
        // Header: 1 required sig, 0 readonly signed, 1 readonly unsigned (recipient is writable, system is readonly)
        val header = byteArrayOf(1, 0, 1)
        val accountKeysBytes = encodeAccountKeys(accountKeys)
        val transferInstruction = buildTransferInstruction(lamports)
        val instructions = encodeInstructions(listOf(transferInstruction))
        return header + accountKeysBytes + blockhash + instructions
    }

    /**
     * Build the full signed transaction bytes (signatures + message) for broadcast.
     *
     * @param messageBytes unsigned message from buildTransferMessage
     * @param signature 64-byte signature of messageBytes by the fee payer
     */
    fun buildSignedTransaction(messageBytes: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == 64)
        val numSigs = writeCompactU16(1)
        return numSigs + signature + messageBytes
    }

    private fun buildTransferInstruction(lamports: Long): Instruction {
        // Program index 2 (system program), accounts [0, 1], data: u32(2) + u64(lamports)
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(TRANSFER_INSTRUCTION_INDEX)
            putLong(lamports)
        }.array()
        return Instruction(programIdIndex = 2, accountIndices = listOf(0, 1), data = data)
    }

    private data class Instruction(
        val programIdIndex: Int,
        val accountIndices: List<Int>,
        val data: ByteArray
    )

    private fun encodeAccountKeys(keys: List<ByteArray>): ByteArray {
        val len = writeCompactU16(keys.size)
        return len + keys.flatMap { it.toList() }.toByteArray()
    }

    private fun encodeInstructions(instructions: List<Instruction>): ByteArray {
        val len = writeCompactU16(instructions.size)
        val body = instructions.map { instr ->
            writeCompactU16(instr.programIdIndex) +
                writeCompactU16(instr.accountIndices.size) +
                instr.accountIndices.flatMap { writeCompactU16(it).toList() }.toByteArray() +
                writeCompactU16(instr.data.size) +
                instr.data
        }.reduce { a, b -> a + b }
        return len + body
    }

    private fun writeCompactU16(value: Int): ByteArray {
        require(value in 0..0xFFFF)
        return when {
            value <= 0x7F -> byteArrayOf(value.toByte())
            value <= 0x3FFF -> byteArrayOf(
                (value and 0x7F or 0x80).toByte(),
                (value shr 7).toByte()
            )
            else -> byteArrayOf(
                (value and 0x7F or 0x80).toByte(),
                (value shr 7 and 0x7F or 0x80).toByte(),
                (value shr 14).toByte()
            )
        }
    }
}
