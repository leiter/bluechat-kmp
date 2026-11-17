package com.bluechat.data.protocol

/**
 * Splits payloads across BLE writes/notifications and puts them back together.
 *
 * A single GATT write carries at most `MTU - 3` bytes, and the default ATT MTU is 23 -- i.e.
 * 20 usable bytes. Without framing, anything longer than that is silently lost or truncated,
 * which is why the app previously could not send a message longer than a short word.
 *
 * Wire layout of one frame:
 *
 * ```
 * offset  size  field
 *      0     1  version
 *      1     1  flags (reserved, must be 0)
 *      2     4  messageId   - groups frames belonging to one payload
 *      6     2  sequence    - 0-based index of this frame
 *      8     2  total       - number of frames in the payload
 *     10     n  payload bytes
 * ```
 *
 * All multi-byte fields are big-endian.
 */
internal object Frame {

    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 10

    /** Largest payload we are willing to reassemble, as a guard against a malicious peer. */
    const val MAX_PAYLOAD_BYTES: Int = 256 * 1024

    data class Parsed(
        val version: Int,
        val messageId: Int,
        val sequence: Int,
        val total: Int,
        val payload: ByteArray,
    ) {
        // ByteArray needs structural equals/hashCode to behave in tests and collections.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return version == other.version &&
                messageId == other.messageId &&
                sequence == other.sequence &&
                total == other.total &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + messageId
            result = 31 * result + sequence
            result = 31 * result + total
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Splits [payload] into frames that each fit within [maxFrameSize] bytes on the wire.
     *
     * @param maxFrameSize the negotiated per-write budget, header included.
     * @throws IllegalArgumentException if [maxFrameSize] leaves no room for payload bytes, or
     *   the payload needs more frames than the 16-bit sequence field can address.
     */
    fun split(messageId: Int, payload: ByteArray, maxFrameSize: Int): List<ByteArray> {
        val chunkSize = maxFrameSize - HEADER_SIZE
        require(chunkSize > 0) {
            "maxFrameSize $maxFrameSize is too small for a $HEADER_SIZE byte header"
        }

        // An empty payload still needs one frame, so the far side sees an empty message
        // rather than nothing at all.
        val total = if (payload.isEmpty()) 1 else (payload.size + chunkSize - 1) / chunkSize
        require(total <= 0xFFFF) {
            "payload of ${payload.size} bytes needs $total frames, more than the 65535 addressable"
        }

        return (0 until total).map { sequence ->
            val start = sequence * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            val chunk = if (start >= end) ByteArray(0) else payload.copyOfRange(start, end)

            ByteArray(HEADER_SIZE + chunk.size).also { frame ->
                frame[0] = VERSION.toByte()
                frame[1] = 0
                frame.putInt(2, messageId)
                frame.putShort(6, sequence)
                frame.putShort(8, total)
                chunk.copyInto(frame, HEADER_SIZE)
            }
        }
    }

    /** Parses one received frame, or returns `null` if it is malformed or an unknown version. */
    fun parse(bytes: ByteArray): Parsed? {
        if (bytes.size < HEADER_SIZE) return null

        val version = bytes[0].toInt() and 0xFF
        if (version != VERSION) return null

        val sequence = bytes.getShort(6)
        val total = bytes.getShort(8)
        if (total == 0 || sequence >= total) return null

        return Parsed(
            version = version,
            messageId = bytes.getInt(2),
            sequence = sequence,
            total = total,
            payload = bytes.copyOfRange(HEADER_SIZE, bytes.size),
        )
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun ByteArray.putShort(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.getInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.getShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
}
