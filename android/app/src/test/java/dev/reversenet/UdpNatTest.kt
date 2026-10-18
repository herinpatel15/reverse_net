package dev.reversenet

import dev.reversenet.nat.Ipv4
import dev.reversenet.nat.Udp
import dev.reversenet.nat.UdpNat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UdpNatTest {
    private val loopback = Protocol.ip(127, 0, 0, 1)

    @Test fun forwardsAndWrapsReplyThenExpires() {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
        val out = LinkedBlockingQueue<ByteArray>()
        var now = 0L
        val nat = UdpNat({ DatagramSocket() }, { out.put(it) }, { now }, idleTimeoutMs = 30_000)

        val seg = Udp.build(Protocol.ESP_IP, loopback, 40000, server.localPort, "ping".toByteArray())
        val raw = Ipv4.build(Ipv4.PROTO_UDP, Protocol.ESP_IP, loopback, seg)
        nat.handle(Ipv4.parse(raw, raw.size)!!)

        val buf = ByteArray(100)
        val got = DatagramPacket(buf, buf.size)
        server.receive(got)
        assertEquals("ping", String(buf, 0, got.length))
        server.send(DatagramPacket("pong".toByteArray(), 4, got.socketAddress))

        val reply = out.poll(5, TimeUnit.SECONDS)!!
        val ip = Ipv4.parse(reply, reply.size)!!
        assertEquals(loopback, ip.src)
        assertEquals(Protocol.ESP_IP, ip.dst)
        val d = Udp.parse(ip)!!
        assertEquals(server.localPort, d.srcPort)
        assertEquals(40000, d.dstPort)
        assertEquals("pong", String(d.payload))

        assertEquals(1, nat.flowCount)
        now = 31_000
        nat.expire()
        assertTrue(nat.flowCount == 0)
        server.close()
    }
}
