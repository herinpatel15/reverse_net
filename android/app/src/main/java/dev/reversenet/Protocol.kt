package dev.reversenet

import java.nio.ByteBuffer

class Frame(val type: Int, val payload: ByteArray)

/** Protocol v1 constants and framing. Keep identical to docs/protocol.md. */
object Protocol {
    const val VERSION = 1
    const val T_HELLO = 0x01
    const val T_HELLO_ACK = 0x02
    const val T_KEEPALIVE = 0x03
    const val T_IP = 0x10
    const val T_CLOSE = 0x7F

    const val ESP_ADDRESS = "192.168.4.1"
    const val ESP_PORT = 5000
    const val MTU = 1400

    const val KEEPALIVE_MS = 5_000L
    const val TIMEOUT_MS = 15_000L
    const val HELLO_RETRY_MS = 2_000L

    /** Tunnel addresses as big-endian ints. */
    val ANDROID_IP = ip(10, 99, 0, 1)
    val ESP_IP = ip(10, 99, 0, 2)

    private val EMPTY = ByteArray(0)

    fun ip(a: Int, b: Int, c: Int, d: Int): Int = (a shl 24) or (b shl 16) or (c shl 8) or d

    fun frame(type: Int, payload: ByteArray = EMPTY): ByteArray =
        ByteBuffer.allocate(3 + payload.size)
            .put(type.toByte()).putShort(payload.size.toShort()).put(payload).array()

    fun parse(data: ByteArray, len: Int): Frame? {
        if (len < 3) return null
        val type = data[0].toInt() and 0xFF
        val plen = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        if (len != 3 + plen) return null
        return Frame(type, data.copyOfRange(3, len))
    }

    fun hello(): ByteArray =
        frame(T_HELLO, byteArrayOf(VERSION.toByte(), (MTU shr 8).toByte(), (MTU and 0xFF).toByte()))
}
