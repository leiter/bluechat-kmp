package com.bluechat.data.protocol

/**
 * Collects [Frame]s arriving from one peer and hands back payloads once they are complete.
 *
 * Frames from different messages can interleave, so partial messages are tracked per
 * `messageId`. Anything that stops arriving part-way is dropped by [evictStaleBefore] rather
 * than being kept forever.
 *
 * Not thread-safe: call it from a single coroutine.
 */
internal class Reassembler {

    private class Partial(
        val total: Int,
        val chunks: Array<ByteArray?>,
        var receivedCount: Int,
        var byteCount: Int,
        var lastUpdatedAt: Long,
    )

    private val partials = mutableMapOf<Int, Partial>()

    /**
     * Feeds one received frame in.
     *
     * @return the complete payload if this frame finished a message, otherwise `null`.
     */
    fun accept(bytes: ByteArray, now: Long): ByteArray? {
        val frame = Frame.parse(bytes) ?: return null

        // Fast path: a message that fits in a single frame never needs buffering.
        if (frame.total == 1) {
            partials.remove(frame.messageId)
            return frame.payload
        }

        val partial = partials.getOrPut(frame.messageId) {
            Partial(
                total = frame.total,
                chunks = arrayOfNulls(frame.total),
                receivedCount = 0,
                byteCount = 0,
                lastUpdatedAt = now,
            )
        }

        // A peer that changes its mind about the frame count mid-message is not something we
        // can reconcile; start over from this frame.
        if (partial.total != frame.total) {
            partials.remove(frame.messageId)
            return accept(bytes, now)
        }

        // Ignore duplicates (a retransmit) rather than double-counting them.
        if (partial.chunks[frame.sequence] != null) return null

        partial.chunks[frame.sequence] = frame.payload
        partial.receivedCount++
        partial.byteCount += frame.payload.size
        partial.lastUpdatedAt = now

        if (partial.byteCount > Frame.MAX_PAYLOAD_BYTES) {
            partials.remove(frame.messageId)
            return null
        }

        if (partial.receivedCount < partial.total) return null

        partials.remove(frame.messageId)
        val out = ByteArray(partial.byteCount)
        var offset = 0
        for (chunk in partial.chunks) {
            val bytesOfChunk = chunk ?: return null
            bytesOfChunk.copyInto(out, offset)
            offset += bytesOfChunk.size
        }
        return out
    }

    /** Drops partial messages last touched before [cutoff], freeing their buffers. */
    fun evictStaleBefore(cutoff: Long) {
        val stale = partials.filterValues { it.lastUpdatedAt < cutoff }.keys
        stale.forEach(partials::remove)
    }

    /** Number of messages currently part-way through reassembly. Exposed for tests. */
    val pendingCount: Int get() = partials.size

    fun clear() = partials.clear()
}
