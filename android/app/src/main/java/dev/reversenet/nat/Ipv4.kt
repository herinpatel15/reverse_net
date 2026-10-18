package dev.reversenet.nat

/** A validated IPv4 packet (no fragments, header checksum verified). */
class Ipv4Packet(val data: ByteArray, val headerLen: Int, val totalLen: Int) {
    val protocol: Int get() = data[9].toInt() and 0xFF
    val src: Int get() = Ipv4.readInt(data, 12)
    val dst: Int get() = Ipv4.readInt(data, 16)
    val payloadLength: Int get() = totalLen - headerLen
    fun payload(): ByteArray = data.copyOfRange(headerLen, totalLen)
}

object Ipv4 {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    fun readInt(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    fun readU16(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    fun writeU16(d: ByteArray, o: Int, v: Int) {
        d[o] = (v ushr 8).toByte()
        d[o + 1] = v.toByte()
    }

    fun writeInt(d: ByteArray, o: Int, v: Int) {
        d[o] = (v ushr 24).toByte()
        d[o + 1] = (v ushr 16).toByte()
        d[o + 2] = (v ushr 8).toByte()
        d[o + 3] = v.toByte()
    }

    fun toBytes(ip: Int): ByteArray = ByteArray(4).also { writeInt(it, 0, ip) }

    fun toString(ip: Int): String =
        "${ip ushr 24 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}"

    /** Returns null for anything malformed, fragmented or with a bad header checksum. */
    fun parse(data: ByteArray, len: Int): Ipv4Packet? {
        if (len < 20 || len > data.size) return null
        if ((data[0].toInt() ushr 4) and 0xF != 4) return null
        val ihl = (data[0].toInt() and 0xF) * 4
        if (ihl < 20 || ihl > len) return null
        val total = readU16(data, 2)
        if (total < ihl || total > len) return null
        if (readU16(data, 6) and 0x3FFF != 0) return null // MF flag or fragment offset
        if (Checksum.compute(data, 0, ihl) != 0) return null
        return Ipv4Packet(data, ihl, total)
    }

    /** Builds a 20-byte-header IPv4 packet with a correct header checksum. */
    fun build(protocol: Int, src: Int, dst: Int, payload: ByteArray, id: Int = 0, ttl: Int = 64): ByteArray {
        val total = 20 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        writeU16(p, 2, total)
        writeU16(p, 4, id)
        writeU16(p, 6, 0x4000) // DF
        p[8] = ttl.toByte()
        p[9] = protocol.toByte()
        writeInt(p, 12, src)
        writeInt(p, 16, dst)
        writeU16(p, 10, Checksum.compute(p, 0, 20))
        System.arraycopy(payload, 0, p, 20, payload.size)
        return p
    }
}
