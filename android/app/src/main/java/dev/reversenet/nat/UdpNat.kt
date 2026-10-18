package dev.reversenet.nat

import dev.reversenet.Protocol
import dev.reversenet.RnLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * UDP NAT. One flow per (source port, destination IP, destination port); each flow owns a socket
 * from [socketFactory] (bound to the cellular network in the app) and a reader thread that wraps
 * replies into IP packets for [sendIp].
 */
class UdpNat(
    private val socketFactory: () -> DatagramSocket?,
    private val sendIp: (ByteArray) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idleTimeoutMs: Long = 30_000,
) {
    private data class Key(val srcPort: Int, val dstIp: Int, val dstPort: Int)

    private inner class Flow(val key: Key, val socket: DatagramSocket) {
        @Volatile var lastActive = clock()
    }

    private val flows = ConcurrentHashMap<Key, Flow>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "udp-nat").apply { isDaemon = true }
    }
    private var ipId = 0

    val flowCount: Int get() = flows.size

    fun handle(pkt: Ipv4Packet) {
        val d = Udp.parse(pkt) ?: return
        val key = Key(d.srcPort, pkt.dst, d.dstPort)
        val flow = synchronized(this) {
            flows[key] ?: open(key)?.also { flows[key] = it }
        } ?: return
        flow.lastActive = clock()
        try {
            val addr = InetAddress.getByAddress(Ipv4.toBytes(pkt.dst))
            flow.socket.send(DatagramPacket(d.payload, d.payload.size, addr, d.dstPort))
        } catch (e: IOException) {
            RnLog.w("udp send failed: ${e.message}")
            close(flow)
        }
    }

    private fun open(key: Key): Flow? {
        val socket = socketFactory()
        if (socket == null) {
            RnLog.w("no cellular network; dropping UDP to ${Ipv4.toString(key.dstIp)}:${key.dstPort}")
            return null
        }
        val flow = Flow(key, socket)
        pool.execute { readLoop(flow) }
        return flow
    }

    private fun readLoop(flow: Flow) {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        try {
            while (!flow.socket.isClosed) {
                flow.socket.receive(pkt)
                flow.lastActive = clock()
                if (pkt.length > Udp.MAX_PAYLOAD) {
                    RnLog.w("udp reply of ${pkt.length} B exceeds tunnel MTU; dropped")
                    continue
                }
                val payload = buf.copyOfRange(0, pkt.length)
                val seg = Udp.build(flow.key.dstIp, Protocol.ESP_IP, flow.key.dstPort, flow.key.srcPort, payload)
                val id = synchronized(this) { ipId++ and 0xFFFF }
                sendIp(Ipv4.build(Ipv4.PROTO_UDP, flow.key.dstIp, Protocol.ESP_IP, seg, id))
            }
        } catch (e: IOException) {
            // socket closed by expire()/closeAll()
        }
    }

    private fun close(flow: Flow) {
        flows.remove(flow.key, flow)
        flow.socket.close()
    }

    /** Close flows idle longer than the timeout. Call about once per second. */
    fun expire() {
        val now = clock()
        flows.values.filter { now - it.lastActive > idleTimeoutMs }.forEach { close(it) }
    }

    fun closeAll() {
        flows.values.toList().forEach { close(it) }
    }
}
