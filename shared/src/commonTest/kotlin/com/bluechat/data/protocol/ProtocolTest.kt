package com.bluechat.data.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameTest {

    /** The default ATT MTU of 23 leaves 20 usable bytes -- the case that used to break. */
    private val minimalFrameSize = 20

    @Test
    fun `single frame round trips`() {
        val payload = "hello".encodeToByteArray()
        val frames = Frame.split(messageId = 7, payload = payload, maxFrameSize = 100)

        assertEquals(1, frames.size)
        val parsed = assertNotNull(Frame.parse(frames[0]))
        assertEquals(7, parsed.messageId)
        assertEquals(0, parsed.sequence)
        assertEquals(1, parsed.total)
        assertContentEquals(payload, parsed.payload)
    }

    @Test
    fun `payload larger than one frame is split and ordered`() {
        val payload = ByteArray(100) { it.toByte() }
        val frames = Frame.split(messageId = 1, payload = payload, maxFrameSize = minimalFrameSize)

        // 20 byte frames minus a 10 byte header leaves 10 payload bytes each.
        assertEquals(10, frames.size)
        frames.forEachIndexed { index, bytes ->
            assertTrue(bytes.size <= minimalFrameSize, "frame $index exceeds the MTU budget")
            val parsed = assertNotNull(Frame.parse(bytes))
            assertEquals(index, parsed.sequence)
            assertEquals(10, parsed.total)
        }
    }

    @Test
    fun `empty payload still produces one frame`() {
        val frames = Frame.split(messageId = 3, payload = ByteArray(0), maxFrameSize = 50)
        assertEquals(1, frames.size)
        val parsed = assertNotNull(Frame.parse(frames[0]))
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun `frame size leaving no room for payload is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Frame.split(messageId = 1, payload = "x".encodeToByteArray(), maxFrameSize = Frame.HEADER_SIZE)
        }
    }

    @Test
    fun `truncated and unknown-version frames are rejected`() {
        assertNull(Frame.parse(ByteArray(Frame.HEADER_SIZE - 1)))

        val frame = Frame.split(1, "x".encodeToByteArray(), 50).single()
        frame[0] = 99 // unknown version
        assertNull(Frame.parse(frame))
    }

    @Test
    fun `frame claiming a sequence beyond its total is rejected`() {
        val frame = Frame.split(1, "x".encodeToByteArray(), 50).single()
        frame[6] = 0; frame[7] = 5 // sequence = 5, total is still 1
        assertNull(Frame.parse(frame))
    }
}

class ReassemblerTest {

    @Test
    fun `reassembles a split payload`() {
        val payload = ByteArray(250) { (it % 251).toByte() }
        val frames = Frame.split(messageId = 42, payload = payload, maxFrameSize = 23)
        val reassembler = Reassembler()

        val completed = frames.mapNotNull { reassembler.accept(it, now = 0) }

        assertEquals(1, completed.size)
        assertContentEquals(payload, completed.single())
        assertEquals(0, reassembler.pendingCount)
    }

    @Test
    fun `reassembles when frames arrive out of order`() {
        val payload = ByteArray(80) { it.toByte() }
        val frames = Frame.split(messageId = 1, payload = payload, maxFrameSize = 30).reversed()
        val reassembler = Reassembler()

        val completed = frames.mapNotNull { reassembler.accept(it, now = 0) }

        assertContentEquals(payload, completed.single())
    }

    @Test
    fun `interleaved messages are kept apart`() {
        val first = ByteArray(60) { 1 }
        val second = ByteArray(60) { 2 }
        val a = Frame.split(messageId = 1, payload = first, maxFrameSize = 30)
        val b = Frame.split(messageId = 2, payload = second, maxFrameSize = 30)
        val reassembler = Reassembler()

        val completed = buildList {
            a.zip(b).forEach { (frameA, frameB) ->
                reassembler.accept(frameA, now = 0)?.let(::add)
                reassembler.accept(frameB, now = 0)?.let(::add)
            }
        }

        assertEquals(2, completed.size)
        assertContentEquals(first, completed[0])
        assertContentEquals(second, completed[1])
    }

    @Test
    fun `duplicate frames do not corrupt the payload`() {
        val payload = ByteArray(60) { it.toByte() }
        val frames = Frame.split(messageId = 1, payload = payload, maxFrameSize = 30)
        val reassembler = Reassembler()

        val completed = buildList {
            frames.forEach { frame ->
                reassembler.accept(frame, now = 0)?.let(::add)
                reassembler.accept(frame, now = 0)?.let(::add) // retransmit
            }
        }

        assertContentEquals(payload, completed.single())
    }

    @Test
    fun `abandoned partial messages are evicted`() {
        val frames = Frame.split(messageId = 1, payload = ByteArray(60), maxFrameSize = 30)
        val reassembler = Reassembler()

        reassembler.accept(frames.first(), now = 1_000)
        assertEquals(1, reassembler.pendingCount)

        reassembler.evictStaleBefore(cutoff = 5_000)
        assertEquals(0, reassembler.pendingCount)
    }
}

class ProtocolCodecTest {

    private fun codec(): ProtocolCodec {
        var id = 0
        return ProtocolCodec(nextMessageId = { id++ })
    }

    @Test
    fun `text envelope round trips across many frames`() {
        val sent = Envelope.Text(
            senderId = "peer-a",
            messageId = "m-1",
            sentAt = 1_700_000_000_000,
            body = "a".repeat(500),
        )
        val encode = codec()
        val decode = codec()

        // 23 byte frames: the worst case the transport can hand us.
        val frames = encode.encode(sent, maxFrameSize = 23)
        assertTrue(frames.size > 1, "a 500 character body must span multiple frames")

        val received = frames.mapNotNull { decode.decode("peer-a", it, now = 0) }
        assertEquals(sent, received.single())
    }

    @Test
    fun `multi-byte characters survive being split across frames`() {
        // decodeToString on an individual frame would corrupt these; only reassembling
        // the bytes first and decoding once keeps them intact.
        val body = "こんにちは🙂 grüße ".repeat(20)
        val sent = Envelope.Text("peer-a", "m-2", 0, body)

        val frames = codec().encode(sent, maxFrameSize = 23)
        val received = codec().decode("peer-a", frames, now = 0)

        assertEquals(body, (received as Envelope.Text).body)
    }

    @Test
    fun `profile envelope round trips`() {
        val sent = Envelope.Profile(senderId = "peer-b", displayName = "Marco's Pixel")
        val frames = codec().encode(sent, maxFrameSize = 100)
        assertEquals(sent, codec().decode("peer-b", frames, now = 0))
    }

    @Test
    fun `frames from different peers do not mix`() {
        val fromA = codec().encode(Envelope.Text("a", "m", 0, "x".repeat(200)), maxFrameSize = 23)
        val fromB = codec().encode(Envelope.Text("b", "m", 0, "y".repeat(200)), maxFrameSize = 23)
        val decode = codec()

        val completed = buildList {
            fromA.zip(fromB).forEach { (a, b) ->
                decode.decode("a", a, now = 0)?.let(::add)
                decode.decode("b", b, now = 0)?.let(::add)
            }
        }

        assertEquals(2, completed.size)
        assertEquals("a", completed[0].senderId)
        assertEquals("b", completed[1].senderId)
    }

    @Test
    fun `garbage from a peer is ignored rather than thrown`() {
        val decode = codec()
        // Well-formed single frame, but the payload is not an envelope.
        val frame = Frame.split(1, "not json".encodeToByteArray(), maxFrameSize = 50).single()
        assertNull(decode.decode("peer", frame, now = 0))
    }

    @Test
    fun `an envelope kind this build does not know is ignored`() {
        val unknown = """{"t":"typing","senderId":"peer-a"}""".encodeToByteArray()
        val frame = Frame.split(1, unknown, maxFrameSize = 100).single()
        assertNull(codec().decode("peer-a", frame, now = 0))
    }

    private fun ProtocolCodec.decode(peer: String, frames: List<ByteArray>, now: Long): Envelope? =
        frames.mapNotNull { decode(peer, it, now) }.singleOrNull()
}
