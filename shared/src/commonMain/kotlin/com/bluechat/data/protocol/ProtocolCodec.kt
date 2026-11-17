package com.bluechat.data.protocol

import kotlinx.serialization.json.Json

/**
 * Turns an [Envelope] into wire frames and back.
 *
 * Encoding is JSON inside the framing from [Frame]. JSON costs more bytes than a binary format
 * would, but once the MTU is negotiated upwards there is ample room, and being able to read a
 * capture is worth a lot while the protocol is still moving.
 */
internal class ProtocolCodec(
    private val nextMessageId: () -> Int,
) {

    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val reassemblers = mutableMapOf<String, Reassembler>()

    /** Encodes [envelope] into frames that each fit within [maxFrameSize] bytes. */
    fun encode(envelope: Envelope, maxFrameSize: Int): List<ByteArray> {
        val payload = json.encodeToString<Envelope>(envelope).encodeToByteArray()
        return Frame.split(nextMessageId(), payload, maxFrameSize)
    }

    /**
     * Feeds one frame received from [peerKey] in.
     *
     * @return the decoded envelope once its last frame arrives, or `null` while the message is
     *   still incomplete, malformed, or of a kind this build does not understand.
     */
    fun decode(peerKey: String, bytes: ByteArray, now: Long): Envelope? {
        val reassembler = reassemblers.getOrPut(peerKey) { Reassembler() }
        val payload = reassembler.accept(bytes, now) ?: return null

        // Never let one peer's malformed payload take the receive loop down.
        return try {
            // decodeToString is only safe on a *reassembled* payload: applying it to an
            // individual frame would corrupt any multi-byte character split across the
            // chunk boundary.
            json.decodeFromString<Envelope>(payload.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    /** Drops in-flight partial messages for a peer that has gone away. */
    fun forget(peerKey: String) {
        reassemblers.remove(peerKey)
    }

    /** Drops partial messages that stopped arriving before [cutoff]. */
    fun evictStaleBefore(cutoff: Long) {
        reassemblers.values.forEach { it.evictStaleBefore(cutoff) }
    }
}
