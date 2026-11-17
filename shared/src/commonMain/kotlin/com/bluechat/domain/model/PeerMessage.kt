package com.bluechat.domain.model

/**
 * Something a peer sent us, already decoded from the wire format.
 *
 * [transportId] is how we *reach* the peer right now -- a Bluetooth address on Android, a
 * CoreBluetooth UUID on iOS. It is not an identity: it changes under address randomisation and
 * means nothing to the other platform. [peerId] is the stable identity the peer told us about
 * itself, and is what conversations are keyed on.
 */
sealed interface PeerMessage {

    val transportId: String
    val peerId: String

    data class Text(
        override val transportId: String,
        override val peerId: String,
        /** Sender-assigned id, used to de-duplicate re-delivered messages. */
        val messageId: String,
        val sentAt: Long,
        val body: String,
    ) : PeerMessage

    /** Identity announcement, sent by both sides as soon as a connection comes up. */
    data class Profile(
        override val transportId: String,
        override val peerId: String,
        val displayName: String,
    ) : PeerMessage

    /** The peer has read the message we sent with [messageId]. */
    data class ReadReceipt(
        override val transportId: String,
        override val peerId: String,
        val messageId: String,
    ) : PeerMessage

    /** Transient typing signal. Never persisted. */
    data class Typing(
        override val transportId: String,
        override val peerId: String,
        val isTyping: Boolean,
    ) : PeerMessage
}
