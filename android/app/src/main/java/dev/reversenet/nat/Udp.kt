package dev.reversenet.nat

class UdpDatagram(val srcPort: Int, val dstPort: Int, val payload: ByteArray)

object Udp {
    /** Largest UDP payload that fits one tunnel packet (MTU 1400 - 20 IP - 8 UDP). */
    const val MAX_PAYLOAD = 1372

    /** Parses the UDP segment of [pkt]; returns null if malformed or the checksum is wrong. */
    fun parse(pkt: Ipv4Packet): UdpDatagram? {
        if (pkt.payloadLength < 8) return null
        val off = pkt.headerLen
        val udpLen = Ipv4.readU16(pkt.data, off + 4)
        if (udpLen < 8 || udpLen > pkt.payloadLength) return null
        if (Ipv4.readU16(pkt.data, off + 6) != 0) { // 0 means "no checksum" in IPv4
            val sum = Checksum.partial(
                pkt.data, off, udpLen,
                Checksum.pseudoHeader(pkt.src, pkt.dst, Ipv4.PROTO_UDP, udpLen),
            )
            if (Checksum.finish(sum) != 0) return null
        }
        return UdpDatagram(
            srcPort = Ipv4.readU16(pkt.data, off),
            dstPort = Ipv4.readU16(pkt.data, off + 2),
            payload = pkt.data.copyOfRange(off + 8, off + udpLen),
        )
    }

    /** Builds a UDP segment (header + payload) with checksum for the given addresses. */
    fun build(src: Int, dst: Int, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val len = 8 + payload.size
        val s = ByteArray(len)
        Ipv4.writeU16(s, 0, srcPort)
        Ipv4.writeU16(s, 2, dstPort)
        Ipv4.writeU16(s, 4, len)
        System.arraycopy(payload, 0, s, 8, payload.size)
        val sum = Checksum.partial(s, 0, len, Checksum.pseudoHeader(src, dst, Ipv4.PROTO_UDP, len))
        val cs = Checksum.finish(sum)
        Ipv4.writeU16(s, 6, if (cs == 0) 0xFFFF else cs)
        return s
    }
}
