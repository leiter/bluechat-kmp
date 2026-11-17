package com.bluechat.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What actually travels between two installs.
 *
 * Before this existed the payload *was* the message text, so there was nowhere to put a sender
 * identity, a message id, or anything that is not chat content. Adding a case here is how
 * receipts, typing indicators and attachments get introduced later -- the polymorphic
 * discriminator means an older build simply ignores a case it does not know (see
 * [ProtocolCodec.decode]).
 */
@Serializable
sealed interface Envelope {

    /** Stable per-install identity of the sender. Survives Bluetooth address randomisation. */
    val senderId: String

    /** A chat message. */
    @Serializable
    @SerialName("text")
    data class Text(
        override val senderId: String,
        /** Sender-assigned id, so the receiver can de-duplicate and later acknowledge it. */
        val messageId: String,
        /** Sender's clock, epoch millis. */
        val sentAt: Long,
        val body: String,
    ) : Envelope

    /**
     * Sent on connect so the peer learns who we are. This is what lets conversations be keyed
     * on a stable identity instead of a MAC address, and it removes the need to broadcast the
     * display name by renaming the phone's Bluetooth adapter.
     */
    @Serializable
    @SerialName("profile")
    data class Profile(
        override val senderId: String,
        val displayName: String,
    ) : Envelope

    /**
     * Acknowledges that a message was read. Carries the id the sender assigned in [Text], so
     * the two sides agree on what is being acknowledged without relying on ordering.
     *
     * Ported from Phase 9's `BleMessageProtocol.TYPE_READ_RECEIPT`, which sent a bare uuid
     * behind a one-byte tag. The behaviour is the same; it now rides the framed protocol, so
     * it is no longer limited to what fits in a single unnegotiated 20-byte write.
     */
    @Serializable
    @SerialName("read")
    data class ReadReceipt(
        override val senderId: String,
        val messageId: String,
    ) : Envelope

    /**
     * Transient "peer is typing" signal. Deliberately carries no id and is never persisted:
     * it is only meaningful while the connection is up.
     *
     * Ported from Phase 9's `BleMessageProtocol.TYPE_TYPING_INDICATOR`.
     */
    @Serializable
    @SerialName("typing")
    data class Typing(
        override val senderId: String,
        val isTyping: Boolean,
    ) : Envelope
}
