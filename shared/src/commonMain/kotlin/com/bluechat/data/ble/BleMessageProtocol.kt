package com.bluechat.data.ble

import kotlin.uuid.Uuid

/**
 * BLE Message Protocol for Bluechat
 *
 * Message Types:
 *   0x00 = Chat message (messageUuid|message)
 *   0x01 = Read receipt (messageUuid)
 *   0x02 = Typing indicator (0 = stopped, 1 = typing)
 */
object BleMessageProtocol {
    const val TYPE_CHAT_MESSAGE: Byte = 0x00
    const val TYPE_READ_RECEIPT: Byte = 0x01
    const val TYPE_TYPING_INDICATOR: Byte = 0x02

    private const val UUID_LENGTH = 36 // Standard UUID string length
    private const val SEPARATOR = '|'

    /**
     * Encodes a chat message with UUID for delivery tracking
     */
    fun encodeChatMessage(messageUuid: String, message: String): ByteArray {
        val payload = "$messageUuid$SEPARATOR$message"
        return byteArrayOf(TYPE_CHAT_MESSAGE) + payload.encodeToByteArray()
    }

    /**
     * Encodes a read receipt for a specific message UUID
     */
    fun encodeReadReceipt(messageUuid: String): ByteArray {
        return byteArrayOf(TYPE_READ_RECEIPT) + messageUuid.encodeToByteArray()
    }

    /**
     * Encodes a typing indicator (true = typing, false = stopped)
     */
    fun encodeTypingIndicator(isTyping: Boolean): ByteArray {
        return byteArrayOf(TYPE_TYPING_INDICATOR, if (isTyping) 1 else 0)
    }

    /**
     * Decodes a BLE message and returns the parsed content
     */
    fun decode(data: ByteArray): BleMessage {
        if (data.isEmpty()) {
            return BleMessage.Unknown(data)
        }

        return when (data[0]) {
            TYPE_CHAT_MESSAGE -> decodeChatMessage(data)
            TYPE_READ_RECEIPT -> decodeReadReceipt(data)
            TYPE_TYPING_INDICATOR -> decodeTypingIndicator(data)
            else -> {
                // Legacy message format (plain text without type prefix)
                BleMessage.LegacyChatMessage(data.decodeToString())
            }
        }
    }

    private fun decodeChatMessage(data: ByteArray): BleMessage {
        if (data.size < 2) {
            return BleMessage.Unknown(data)
        }

        val payload = data.copyOfRange(1, data.size).decodeToString()
        val separatorIndex = payload.indexOf(SEPARATOR)

        return if (separatorIndex > 0 && separatorIndex < payload.length - 1) {
            val messageUuid = payload.substring(0, separatorIndex)
            val message = payload.substring(separatorIndex + 1)
            BleMessage.ChatMessage(messageUuid, message)
        } else {
            // Malformed message, treat as legacy
            BleMessage.LegacyChatMessage(payload)
        }
    }

    private fun decodeReadReceipt(data: ByteArray): BleMessage {
        if (data.size < 2) {
            return BleMessage.Unknown(data)
        }

        val messageUuid = data.copyOfRange(1, data.size).decodeToString()
        return BleMessage.ReadReceipt(messageUuid)
    }

    private fun decodeTypingIndicator(data: ByteArray): BleMessage {
        if (data.size < 2) {
            return BleMessage.Unknown(data)
        }

        val isTyping = data[1] == 1.toByte()
        return BleMessage.TypingIndicator(isTyping)
    }

    /**
     * Generates a new UUID for message tracking
     */
    fun generateMessageUuid(): String {
        return Uuid.random().toString()
    }

    /**
     * Checks if data appears to be in the new protocol format
     */
    fun isNewProtocol(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        return data[0] in listOf(TYPE_CHAT_MESSAGE, TYPE_READ_RECEIPT, TYPE_TYPING_INDICATOR)
    }
}

/**
 * Sealed class representing different BLE message types
 */
sealed class BleMessage {
    data class ChatMessage(val messageUuid: String, val content: String) : BleMessage()
    data class LegacyChatMessage(val content: String) : BleMessage()
    data class ReadReceipt(val messageUuid: String) : BleMessage()
    data class TypingIndicator(val isTyping: Boolean) : BleMessage()
    data class Unknown(val rawData: ByteArray) : BleMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Unknown
            return rawData.contentEquals(other.rawData)
        }

        override fun hashCode(): Int {
            return rawData.contentHashCode()
        }
    }
}
