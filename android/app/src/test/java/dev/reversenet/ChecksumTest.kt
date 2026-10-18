package dev.reversenet

import dev.reversenet.nat.Checksum
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun rfc1071Example() {
        // RFC 1071 section 3: words 0001 f203 f4f5 f6f7 -> sum ddf2, checksum 220d
        val data = bytes(0x00, 0x01, 0xf2, 0x03, 0xf4, 0xf5, 0xf6, 0xf7)
        assertEquals(0x220d, Checksum.compute(data, 0, data.size))
    }

    @Test fun oddLengthPadsWithZero() {
        val data = bytes(0x12, 0x34, 0x56)
        assertEquals(Checksum.finish(0x1234L + 0x5600L), Checksum.compute(data, 0, 3))
    }

    @Test fun validDataSumsToZero() {
        val data = bytes(0x00, 0x01, 0xf2, 0x03, 0xf4, 0xf5, 0xf6, 0xf7, 0x22, 0x0d)
        assertEquals(0, Checksum.compute(data, 0, data.size))
    }
}
