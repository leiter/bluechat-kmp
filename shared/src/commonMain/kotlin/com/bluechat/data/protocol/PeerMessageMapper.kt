package com.bluechat.data.protocol

import com.bluechat.domain.model.PeerMessage

/** Maps a decoded wire [Envelope] onto the domain type, keeping the wire format in this layer. */
internal fun Envelope.toPeerMessage(transportId: String): PeerMessage = when (this) {
    is Envelope.Text -> PeerMessage.Text(
        transportId = transportId,
        peerId = senderId,
        messageId = messageId,
        sentAt = sentAt,
        body = body,
    )

    is Envelope.Profile -> PeerMessage.Profile(
        transportId = transportId,
        peerId = senderId,
        displayName = displayName,
    )

    is Envelope.ReadReceipt -> PeerMessage.ReadReceipt(
        transportId = transportId,
        peerId = senderId,
        messageId = messageId,
    )

    is Envelope.Typing -> PeerMessage.Typing(
        transportId = transportId,
        peerId = senderId,
        isTyping = isTyping,
    )
}
