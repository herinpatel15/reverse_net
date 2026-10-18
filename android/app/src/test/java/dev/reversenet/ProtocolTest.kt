package dev.reversenet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test fun helloBytesMatchSpec() {
        // type 0x01, length 0x0003, version 1, MTU 1400 = 0x0578
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x03, 0x01, 0x05, 0x78), Protocol.hello())
    }

    @Test fun frameRoundTrip() {
        val f = Protocol.frame(Protocol.T_IP, byteArrayOf(9, 8, 7))
        val parsed = Protocol.parse(f, f.size)!!
        assertEquals(Protocol.T_IP, parsed.type)
        assertArrayEquals(byteArrayOf(9, 8, 7), parsed.payload)
    }

    @Test fun lengthMismatchRejected() {
        val f = Protocol.frame(Protocol.T_IP, byteArrayOf(1, 2))
        assertNull(Protocol.parse(f, f.size - 1))
        assertNull(Protocol.parse(f, 2))
    }

    @Test fun keepaliveIsThreeBytes() {
        assertArrayEquals(byteArrayOf(0x03, 0, 0), Protocol.frame(Protocol.T_KEEPALIVE))
    }
}
