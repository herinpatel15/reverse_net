package dev.reversenet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the real LinkLoop against a fake ESP32 on loopback UDP. */
class LinkLoopTest {
    @Test fun handshakeThenIpDelivery() {
        val lo = InetAddress.getLoopbackAddress()
        val fakeEsp = DatagramSocket(0, lo).apply { soTimeout = 5000 }
        val appSock = DatagramSocket(0, lo)
        val up = CountDownLatch(1)
        var received: ByteArray? = null
        val gotIp = CountDownLatch(1)

        val loop = LinkLoop(
            appSock, InetSocketAddress(lo, fakeEsp.localPort),
            onIp = { received = it; gotIp.countDown() },
            onState = { if (it) up.countDown() },
        )
        val t = Thread { loop.run() }.also { it.start() }

        val buf = ByteArray(2048)
        val hello = DatagramPacket(buf, buf.size)
        fakeEsp.receive(hello)
        assertArrayEquals(Protocol.hello(), buf.copyOf(hello.length))

        val ack = Protocol.frame(Protocol.T_HELLO_ACK, byteArrayOf(1, 0x05, 0x78))
        fakeEsp.send(DatagramPacket(ack, ack.size, hello.socketAddress))
        assertTrue(up.await(5, TimeUnit.SECONDS))

        val ip = Protocol.frame(Protocol.T_IP, byteArrayOf(1, 2, 3))
        fakeEsp.send(DatagramPacket(ip, ip.size, hello.socketAddress))
        assertTrue(gotIp.await(5, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(1, 2, 3), received)

        // sendIp reaches the fake ESP32 as an IP_PACKET frame
        assertTrue(loop.sendIp(byteArrayOf(4, 5)))
        val out = DatagramPacket(buf, buf.size)
        fakeEsp.receive(out)
        assertEquals(Protocol.T_IP, Protocol.parse(buf, out.length)!!.type)

        loop.stop()
        t.join(5000)
        fakeEsp.close()
        appSock.close()
    }
}
