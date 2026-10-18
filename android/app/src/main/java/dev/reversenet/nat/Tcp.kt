package dev.reversenet.nat

class TcpSegment(
    val srcPort: Int,
    val dstPort: Int,
    val seq: Int,
    val ackNum: Int,
    val flags: Int,
    val window: Int,
    val mss: Int?,
    val payload: ByteArray,
) {
    val fin get() = flags and Tcp.FIN != 0
    val syn get() = flags and Tcp.SYN != 0
    val rst get() = flags and Tcp.RST != 0
    val ack get() = flags and Tcp.ACK != 0

    /** Sequence space used by this segment (SYN and FIN count as one byte each). */
    val seqLength get() = payload.size + (if (syn) 1 else 0) + (if (fin) 1 else 0)
}

/** TCP segment parsing and building (RFC 793). Only the MSS option is understood. */
object Tcp {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    /** MSS offered to the ESP32: tunnel MTU 1400 - 20 IP - 20 TCP. */
    const val MSS = 1360

    private val EMPTY = ByteArray(0)

    /** Returns null when malformed or when the checksum does not verify. */
    fun parse(pkt: Ipv4Packet): TcpSegment? {
        val len = pkt.payloadLength
        if (len < 20) return null
        val d = pkt.data
        val o = pkt.headerLen
        val dataOff = ((d[o + 12].toInt() and 0xFF) ushr 4) * 4
        if (dataOff < 20 || dataOff > len) return null
        val sum = Checksum.partial(d, o, len, Checksum.pseudoHeader(pkt.src, pkt.dst, Ipv4.PROTO_TCP, len))
        if (Checksum.finish(sum) != 0) return null

        var mss: Int? = null
        var i = o + 20
        val optEnd = o + dataOff
        while (i < optEnd) {
            val kind = d[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= optEnd) break
            val optLen = d[i + 1].toInt() and 0xFF
            if (optLen < 2 || i + optLen > optEnd) break
            if (kind == 2 && optLen == 4) mss = Ipv4.readU16(d, i + 2)
            i += optLen
        }
        return TcpSegment(
            srcPort = Ipv4.readU16(d, o),
            dstPort = Ipv4.readU16(d, o + 2),
            seq = Ipv4.readInt(d, o + 4),
            ackNum = Ipv4.readInt(d, o + 8),
            flags = d[o + 13].toInt() and 0x3F,
            window = Ipv4.readU16(d, o + 14),
            mss = mss,
            payload = d.copyOfRange(o + dataOff, o + len),
        )
    }

    /** Builds a TCP segment (header, optional MSS option, payload) with a correct checksum. */
    fun build(
        src: Int, dst: Int, srcPort: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray = EMPTY, mss: Int? = null,
    ): ByteArray {
        val hdr = if (mss != null) 24 else 20
        val len = hdr + payload.size
        val s = ByteArray(len)
        Ipv4.writeU16(s, 0, srcPort)
        Ipv4.writeU16(s, 2, dstPort)
        Ipv4.writeInt(s, 4, seq)
        Ipv4.writeInt(s, 8, ack)
        s[12] = ((hdr / 4) shl 4).toByte()
        s[13] = flags.toByte()
        Ipv4.writeU16(s, 14, window)
        if (mss != null) {
            s[20] = 2
            s[21] = 4
            Ipv4.writeU16(s, 22, mss)
        }
        System.arraycopy(payload, 0, s, hdr, payload.size)
        val sum = Checksum.partial(s, 0, len, Checksum.pseudoHeader(src, dst, Ipv4.PROTO_TCP, len))
        Ipv4.writeU16(s, 16, Checksum.finish(sum))
        return s
    }
}
