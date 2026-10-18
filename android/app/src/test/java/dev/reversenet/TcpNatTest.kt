package dev.reversenet

import dev.reversenet.nat.Ipv4
import dev.reversenet.nat.Tcp
import dev.reversenet.nat.TcpNat
import dev.reversenet.nat.TcpSegment
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays the ESP32 against the TCP NAT, with a real loopback server standing in for the internet. */
class TcpNatTest {
    private val loopback = Protocol.ip(127, 0, 0, 1)
    private val espPort = 40000
    private val espIsn = 1000
    private val out = LinkedBlockingQueue<ByteArray>()
    private lateinit var server: ServerSocket
    private lateinit var nat: TcpNat
    private var now = 0L

    @Before fun setUp() {
        server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
        nat = newNat()
    }

    @After fun tearDown() {
        nat.closeAll()
        server.close()
    }

    private fun newNat(connect: (Int, Int) -> Socket? = { _, port -> Socket(InetAddress.getLoopbackAddress(), port) }) =
        TcpNat(connect, { out.put(it) }, { now }, Random(1), rtoMs = 1000)

    private fun toNat(seq: Int, ack: Int, flags: Int, payload: ByteArray = ByteArray(0), window: Int = 8192,
                      mss: Int? = null, port: Int = server.localPort) {
        val seg = Tcp.build(Protocol.ESP_IP, loopback, espPort, port, seq, ack, flags, window, payload, mss)
        val raw = Ipv4.build(Ipv4.PROTO_TCP, Protocol.ESP_IP, loopback, seg)
        nat.handle(Ipv4.parse(raw, raw.size)!!)
    }

    private fun fromNat(): TcpSegment {
        val raw = out.poll(5, TimeUnit.SECONDS)
        assertNotNull("no packet from NAT", raw)
        val ip = Ipv4.parse(raw!!, raw.size)!!
        assertEquals(loopback, ip.src)
        assertEquals(Protocol.ESP_IP, ip.dst)
        return Tcp.parse(ip)!! // also verifies the checksum
    }

    private fun assertNothing() = assertEquals(null, out.poll(300, TimeUnit.MILLISECONDS))

    /** Three-way handshake; returns our ISN (the NAT's). */
    private fun handshake(window: Int = 8192): Int {
        toNat(espIsn, 0, Tcp.SYN, window = window, mss = 1460)
        val synAck = fromNat()
        assertEquals(Tcp.SYN or Tcp.ACK, synAck.flags)
        assertEquals(espIsn + 1, synAck.ackNum)
        assertEquals(Tcp.MSS, synAck.mss)
        assertEquals(server.localPort, synAck.srcPort)
        assertEquals(espPort, synAck.dstPort)
        toNat(espIsn + 1, synAck.seq + 1, Tcp.ACK, window = window)
        return synAck.seq
    }

    @Test fun segmentBuildParseRoundTrip() {
        val seg = Tcp.build(Protocol.ESP_IP, loopback, 1, 2, -5, 77, Tcp.SYN or Tcp.ACK, 1000, "abc".toByteArray(), 1360)
        val raw = Ipv4.build(Ipv4.PROTO_TCP, Protocol.ESP_IP, loopback, seg)
        val p = Tcp.parse(Ipv4.parse(raw, raw.size)!!)!!
        assertEquals(-5, p.seq)
        assertEquals(77, p.ackNum)
        assertEquals(1360, p.mss)
        assertEquals(1000, p.window)
        assertTrue(p.syn && p.ack && !p.fin)
        assertEquals("abc", String(p.payload))
    }

    @Test fun corruptedSegmentRejected() {
        val seg = Tcp.build(Protocol.ESP_IP, loopback, 1, 2, 1, 1, Tcp.ACK, 100, "abc".toByteArray())
        seg[seg.size - 1] = (seg[seg.size - 1] + 1).toByte()
        val raw = Ipv4.build(Ipv4.PROTO_TCP, Protocol.ESP_IP, loopback, seg)
        assertEquals(null, Tcp.parse(Ipv4.parse(raw, raw.size)!!))
    }

    @Test fun dataFlowsBothWaysThenGracefulClose() {
        val isn = handshake()
        val conn = server.accept().apply { soTimeout = 5000 }

        toNat(espIsn + 1, isn + 1, Tcp.PSH or Tcp.ACK, "hello".toByteArray())
        val ack = fromNat()
        assertEquals(Tcp.ACK, ack.flags)
        assertEquals(espIsn + 6, ack.ackNum)
        val buf = ByteArray(16)
        assertEquals(5, conn.getInputStream().read(buf))
        assertEquals("hello", String(buf, 0, 5))

        conn.getOutputStream().write("world".toByteArray())
        val data = fromNat()
        assertEquals(isn + 1, data.seq)
        assertEquals("world", String(data.payload))
        toNat(espIsn + 6, isn + 6, Tcp.ACK)
        assertNothing()

        // ESP32 closes first
        toNat(espIsn + 6, isn + 6, Tcp.FIN or Tcp.ACK)
        assertEquals(espIsn + 7, fromNat().ackNum)
        assertEquals(-1, conn.getInputStream().read(buf))
        conn.close()
        val fin = fromNat()
        assertTrue(fin.fin)
        assertEquals(isn + 6, fin.seq)
        assertEquals(1, nat.flowCount)
        toNat(espIsn + 7, isn + 7, Tcp.ACK)
        assertEquals(0, nat.flowCount)
    }

    @Test fun serverClosesFirst() {
        val isn = handshake()
        val conn = server.accept()
        conn.close()
        val fin = fromNat()
        assertTrue(fin.fin)
        assertEquals(isn + 1, fin.seq)
        toNat(espIsn + 1, isn + 2, Tcp.ACK)
        toNat(espIsn + 1, isn + 2, Tcp.FIN or Tcp.ACK)
        assertEquals(espIsn + 2, fromNat().ackNum)
        assertEquals(0, nat.flowCount)
    }

    @Test fun refusedConnectionGetsRst() {
        nat = newNat { _, _ -> null }
        toNat(espIsn, 0, Tcp.SYN, mss = 1460, port = 9)
        val rst = fromNat()
        assertTrue(rst.rst && rst.ack)
        assertEquals(espIsn + 1, rst.ackNum)
        assertEquals(0, nat.flowCount)
    }

    @Test fun straySegmentGetsRst() {
        toNat(5, 77, Tcp.ACK, "x".toByteArray())
        val rst = fromNat()
        assertTrue(rst.rst)
        assertEquals(77, rst.seq)
    }

    @Test fun duplicateAndOutOfOrderDataNotDelivered() {
        val isn = handshake()
        val conn = server.accept().apply { soTimeout = 400 }
        // gap: bytes 3.. arrive before 0..2
        toNat(espIsn + 4, isn + 1, Tcp.ACK, "def".toByteArray())
        assertEquals(espIsn + 1, fromNat().ackNum)
        toNat(espIsn + 1, isn + 1, Tcp.ACK, "abc".toByteArray())
        assertEquals(espIsn + 4, fromNat().ackNum)
        // retransmit of "abc" plus new "def"
        toNat(espIsn + 1, isn + 1, Tcp.ACK, "abcdef".toByteArray())
        assertEquals(espIsn + 7, fromNat().ackNum)
        val buf = ByteArray(16)
        var got = 0
        try {
            while (got < 6) got += conn.getInputStream().read(buf, got, 16 - got)
        } catch (_: SocketTimeoutException) { }
        assertEquals("abcdef", String(buf, 0, got))
    }

    @Test fun unackedDataIsRetransmitted() {
        val isn = handshake()
        val conn = server.accept()
        conn.getOutputStream().write("data".toByteArray())
        val first = fromNat()
        assertEquals("data", String(first.payload))
        now = 500
        nat.tick()
        assertNothing()
        now = 1500
        nat.tick()
        val again = fromNat()
        assertEquals(first.seq, again.seq)
        assertEquals("data", String(again.payload))
        toNat(espIsn + 1, isn + 5, Tcp.ACK)
        now = 10_000
        nat.tick()
        assertNothing()
    }

    @Test fun peerWindowLimitsSegmentSize() {
        handshake(window = 10)
        val conn = server.accept()
        conn.getOutputStream().write(ByteArray(100) { it.toByte() })
        conn.getOutputStream().flush()
        val first = fromNat()
        assertTrue("segment ${first.payload.size} B exceeds window", first.payload.size <= 10)
        var total = first.payload.size
        assertTrue(total > 0)
        // ACK everything so far and open the window: the rest follows
        toNat(espIsn + 1, first.seq + total, Tcp.ACK, window = 4096)
        while (total < 100) {
            val s = fromNat()
            total += s.payload.size
        }
        assertEquals(100, total)
    }

    @Test fun rstFromEspClosesSocket() {
        handshake()
        val conn = server.accept().apply { soTimeout = 5000 }
        toNat(espIsn + 1, 0, Tcp.RST)
        assertEquals(0, nat.flowCount)
        val r = try { conn.getInputStream().read() } catch (_: java.io.IOException) { -1 }
        assertEquals(-1, r)
    }

    @Test fun synRetransmitGetsSynAckAgain() {
        toNat(espIsn, 0, Tcp.SYN, mss = 1460)
        val first = fromNat()
        toNat(espIsn, 0, Tcp.SYN, mss = 1460)
        val second = fromNat()
        assertEquals(first.seq, second.seq)
        assertEquals(Tcp.SYN or Tcp.ACK, second.flags)
        assertFalse(second.fin)
    }
}
