package dev.reversenet.nat

import dev.reversenet.Protocol
import dev.reversenet.RnLog

/** Entry point for every IP packet received from the ESP32 (the doc's `handleIp`). */
class PacketDispatcher(
    private val udp: UdpNat,
    private val tcp: TcpNat,
    private val sendIp: (ByteArray) -> Unit,
    private val debug: () -> Boolean = { false },
) {
    fun handleIp(data: ByteArray) {
        if (debug()) RnLog.d("rx ${data.size} B: ${hex(data)}")
        val pkt = Ipv4.parse(data, data.size)
        if (pkt == null) {
            RnLog.w("dropped malformed IPv4 packet (${data.size} B)")
            return
        }
        if (pkt.src != Protocol.ESP_IP) return
        RnLog.d("ip proto=${pkt.protocol} ${Ipv4.toString(pkt.src)} -> ${Ipv4.toString(pkt.dst)} len=${pkt.totalLen}")
        when (pkt.protocol) {
            Ipv4.PROTO_ICMP -> IcmpHandler.reply(pkt)?.let(sendIp)
            Ipv4.PROTO_UDP -> udp.handle(pkt)
            Ipv4.PROTO_TCP -> tcp.handle(pkt)
            else -> Unit
        }
    }

    companion object {
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}
