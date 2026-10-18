package dev.reversenet.nat

import dev.reversenet.Protocol

/** Answers ICMP echo requests sent to the tunnel gateway (10.99.0.1). */
object IcmpHandler {
    private const val ECHO_REQUEST = 8
    private const val ECHO_REPLY = 0

    /** Returns the IP packet to send back, or null if [pkt] is not an echo request to the gateway. */
    fun reply(pkt: Ipv4Packet): ByteArray? {
        if (pkt.protocol != Ipv4.PROTO_ICMP || pkt.dst != Protocol.ANDROID_IP) return null
        val len = pkt.payloadLength
        if (len < 8) return null
        val icmp = pkt.payload()
        if (icmp[0].toInt() != ECHO_REQUEST) return null
        if (Checksum.compute(icmp, 0, len) != 0) return null
        icmp[0] = ECHO_REPLY.toByte()
        Ipv4.writeU16(icmp, 2, 0)
        Ipv4.writeU16(icmp, 2, Checksum.compute(icmp, 0, len))
        return Ipv4.build(Ipv4.PROTO_ICMP, pkt.dst, pkt.src, icmp)
    }
}
