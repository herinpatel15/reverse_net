package dev.reversenet

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Protocol v1 handshake and keepalive on [socket] (bound to the Wi-Fi network by the caller).
 * [run] blocks until [stop] is called or the socket fails; run it on its own thread.
 */
class LinkLoop(
    private val socket: DatagramSocket,
    private val esp: InetSocketAddress,
    private val onIp: (ByteArray) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onTick: () -> Unit = {},
) {
    @Volatile var up = false
        private set
    @Volatile private var stopped = false

    private fun nowMs() = System.nanoTime() / 1_000_000

    private fun send(data: ByteArray) {
        try {
            socket.send(DatagramPacket(data, data.size, esp))
        } catch (e: IOException) {
            RnLog.w("send failed: ${e.message}")
        }
    }

    /** Wraps [packet] in an IP_PACKET frame. Returns false when the link is down. */
    fun sendIp(packet: ByteArray): Boolean {
        if (!up || packet.size > Protocol.MTU) return false
        send(Protocol.frame(Protocol.T_IP, packet))
        return true
    }

    fun stop() {
        stopped = true
        if (up) send(Protocol.frame(Protocol.T_CLOSE))
        setUp(false)
    }

    private fun setUp(value: Boolean) {
        if (up == value) return
        up = value
        RnLog.i(if (value) "link up" else "link down")
        onState(value)
    }

    fun run() {
        socket.soTimeout = 1000
        val hello = Protocol.hello()
        val buf = ByteArray(2048)
        val start = nowMs()
        var lastRx = start
        var lastHello = start - Protocol.HELLO_RETRY_MS - 1
        var lastKa = start
        while (!stopped) {
            val now = nowMs()
            if (up && now - lastRx > Protocol.TIMEOUT_MS) setUp(false)
            if (!up && now - lastHello > Protocol.HELLO_RETRY_MS) {
                send(hello)
                lastHello = now
            }
            if (up && now - lastKa > Protocol.KEEPALIVE_MS) {
                send(Protocol.frame(Protocol.T_KEEPALIVE))
                lastKa = now
            }
            onTick()
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (!stopped) RnLog.w("receive failed: ${e.message}")
                break
            }
            if (pkt.socketAddress != esp) continue
            val frame = Protocol.parse(buf, pkt.length) ?: continue
            lastRx = nowMs()
            when (frame.type) {
                Protocol.T_HELLO_ACK ->
                    if (frame.payload.isNotEmpty() && frame.payload[0].toInt() == Protocol.VERSION) {
                        lastKa = lastRx
                        setUp(true)
                    }
                Protocol.T_IP -> if (up) onIp(frame.payload)
                Protocol.T_CLOSE -> setUp(false)
            }
        }
        setUp(false)
    }
}
