package dev.reversenet.nat

import dev.reversenet.Protocol
import dev.reversenet.RnLog
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * User-space TCP NAT. The ESP32 talks TCP to us as if we were the remote server; for every
 * connection we open a real socket from [connector] (bound to the cellular network in the app)
 * and translate between the two.
 *
 * One [Flow] per (ESP32 port, destination IP, destination port). All state of a flow is guarded by
 * its own lock. Threads per flow: a connector (until connected), a socket reader and a socket
 * writer. See docs/tcp.md for the state machine and the simplifications.
 */
class TcpNat(
    private val connector: (dstIp: Int, dstPort: Int) -> Socket?,
    private val sendIp: (ByteArray) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = SecureRandom(),
    private val rtoMs: Long = 1_000,
    private val idleTimeoutMs: Long = 300_000,
    private val connectTimeoutMs: Long = 15_000,
    private val maxFlows: Int = 64,
) {
    private data class Key(val srcPort: Int, val dstIp: Int, val dstPort: Int)

    private enum class State { CONNECTING, SYN_RCVD, ESTABLISHED, CLOSED }

    private class Unacked(val seq: Int, val data: ByteArray, val fin: Boolean, var sentAt: Long) {
        var retries = 0
        val end get() = seq + data.size + (if (fin) 1 else 0)
    }

    private inner class Flow(val key: Key, val clientIsn: Int, val mss: Int, var peerWindow: Int) {
        val lock = ReentrantLock()
        val cond = lock.newCondition()
        val createdAt = clock()
        var lastActive = createdAt
        var state = State.CONNECTING
        var socket: Socket? = null

        val ourIsn = random.nextInt()
        var rcvNxt = clientIsn + 1      // next byte expected from the ESP32
        var sndUna = ourIsn             // oldest unacknowledged byte we sent
        var sndNxt = ourIsn             // next byte we will send

        var clientFin = false
        var serverFinSent = false
        var writerDone = false
        var synSentAt = 0L
        var synRetries = 0
        val unacked = ArrayDeque<Unacked>()
        val writeQueue = LinkedBlockingQueue<ByteArray>()
    }

    private val flows = ConcurrentHashMap<Key, Flow>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "tcp-nat").apply { isDaemon = true }
    }

    val flowCount: Int get() = flows.size

    // ---- packets from the ESP32 -------------------------------------------------------------

    fun handle(pkt: Ipv4Packet) {
        val seg = Tcp.parse(pkt) ?: return
        val key = Key(seg.srcPort, pkt.dst, seg.dstPort)
        val flow = flows[key]
        if (flow == null) {
            newFlow(key, seg)
            return
        }
        flow.lock.withLock { onSegment(flow, seg) }
    }

    private fun newFlow(key: Key, seg: TcpSegment) {
        if (seg.rst) return
        if (!seg.syn || seg.ack || flows.size >= maxFlows) {
            rejectWithRst(key, seg)
            return
        }
        val flow = Flow(key, seg.seq, minOf(seg.mss ?: 536, Tcp.MSS), seg.window)
        if (flows.putIfAbsent(key, flow) != null) return
        pool.execute { connect(flow) }
    }

    /** RFC 793 reset generation for a segment that belongs to no flow. */
    private fun rejectWithRst(key: Key, seg: TcpSegment) {
        if (seg.ack) {
            emit(key, seg.ackNum, 0, Tcp.RST, ByteArray(0), null)
        } else {
            emit(key, 0, seg.seq + seg.seqLength, Tcp.RST or Tcp.ACK, ByteArray(0), null)
        }
    }

    private fun connect(flow: Flow) {
        val socket = try {
            connector(flow.key.dstIp, flow.key.dstPort)
        } catch (e: IOException) {
            null
        }
        flow.lock.withLock {
            if (flow.state != State.CONNECTING) {
                socket?.close()
                return
            }
            if (socket == null) {
                RnLog.w("tcp connect to ${Ipv4.toString(flow.key.dstIp)}:${flow.key.dstPort} failed")
                emit(flow.key, 0, flow.clientIsn + 1, Tcp.RST or Tcp.ACK, ByteArray(0), null)
                discard(flow)
                return
            }
            flow.socket = socket
            flow.state = State.SYN_RCVD
            flow.sndNxt = flow.ourIsn + 1
            sendSynAck(flow)
        }
    }

    private fun sendSynAck(flow: Flow) {
        flow.synSentAt = clock()
        emit(flow.key, flow.ourIsn, flow.clientIsn + 1, Tcp.SYN or Tcp.ACK, ByteArray(0), Tcp.MSS)
    }

    /** Called with the flow lock held. */
    private fun onSegment(flow: Flow, seg: TcpSegment) {
        flow.lastActive = clock()
        if (seg.rst) {
            abort(flow, sendRst = false)
            return
        }
        when (flow.state) {
            State.CONNECTING, State.CLOSED -> return
            State.SYN_RCVD -> {
                if (seg.syn) { sendSynAck(flow); return }
                if (!seg.ack || seg.ackNum != flow.ourIsn + 1) return
                flow.sndUna = seg.ackNum
                flow.peerWindow = seg.window
                flow.state = State.ESTABLISHED
                startIo(flow)
            }
            State.ESTABLISHED -> Unit
        }
        if (seg.syn) return
        if (seg.ack) processAck(flow, seg)
        processData(flow, seg)
        maybeFinish(flow)
    }

    private fun processAck(flow: Flow, seg: TcpSegment) {
        val ack = seg.ackNum
        if (lt(flow.sndUna, ack) && le(ack, flow.sndNxt)) {
            flow.sndUna = ack
            while (flow.unacked.isNotEmpty() && le(flow.unacked.first().end, ack)) flow.unacked.removeFirst()
        }
        flow.peerWindow = seg.window
        flow.cond.signalAll()
    }

    private fun processData(flow: Flow, seg: TcpSegment) {
        if (seg.payload.isEmpty() && !seg.fin) return
        var data = seg.payload
        var fin = seg.fin
        var seq = seg.seq
        val skip = flow.rcvNxt - seq
        if (skip > 0) { // retransmission: drop the part we already have
            if (skip >= data.size) {
                if (skip > data.size) fin = false // the FIN itself was already consumed
                data = ByteArray(0)
            } else {
                data = data.copyOfRange(skip, data.size)
            }
            seq = flow.rcvNxt
        }
        if (seq != flow.rcvNxt) { // a gap: ask again for what we expect
            sendAck(flow)
            return
        }
        if (data.isNotEmpty()) {
            flow.rcvNxt += data.size
            flow.writeQueue.put(data)
        }
        if (fin && !flow.clientFin) {
            flow.clientFin = true
            flow.rcvNxt += 1
            flow.writeQueue.put(FIN_MARK)
        }
        sendAck(flow)
    }

    // ---- socket side --------------------------------------------------------------------------

    private fun startIo(flow: Flow) {
        val socket = flow.socket ?: return
        pool.execute { writeLoop(flow, socket) }
        pool.execute { readLoop(flow, socket) }
    }

    /** ESP32 -> internet. Runs until the FIN marker or an abort. */
    private fun writeLoop(flow: Flow, socket: Socket) {
        try {
            val out = socket.getOutputStream()
            while (true) {
                val d = flow.writeQueue.take()
                if (d === STOP) break
                if (d === FIN_MARK) {
                    socket.shutdownOutput()
                    break
                }
                out.write(d)
            }
        } catch (e: IOException) {
            flow.lock.withLock { abort(flow, sendRst = true) }
        } catch (e: InterruptedException) {
            return
        }
        flow.lock.withLock {
            flow.writerDone = true
            if (flow.state == State.CLOSED) closeQuietly(socket)
        }
    }

    /** Internet -> ESP32. Sends at most what the ESP32's window allows. */
    private fun readLoop(flow: Flow, socket: Socket) {
        val buf = ByteArray(Tcp.MSS)
        try {
            val input = socket.getInputStream()
            while (true) {
                val allowed = flow.lock.withLock {
                    while (flow.state != State.CLOSED && window(flow) <= 0) {
                        flow.cond.await(500, TimeUnit.MILLISECONDS)
                    }
                    if (flow.state == State.CLOSED) return
                    minOf(window(flow), flow.mss)
                }
                val n = input.read(buf, 0, allowed)
                flow.lock.withLock {
                    if (flow.state == State.CLOSED) return
                    if (n < 0) {
                        sendFin(flow)
                        maybeFinish(flow)
                        return
                    }
                    sendData(flow, buf.copyOf(n))
                }
            }
        } catch (e: IOException) {
            flow.lock.withLock { abort(flow, sendRst = true) }
        }
    }

    // ---- sending to the ESP32 (flow lock held) ---------------------------------------------

    private fun window(flow: Flow): Int = flow.peerWindow - (flow.sndNxt - flow.sndUna)

    private fun sendData(flow: Flow, data: ByteArray) {
        flow.unacked.addLast(Unacked(flow.sndNxt, data, false, clock()))
        emit(flow.key, flow.sndNxt, flow.rcvNxt, Tcp.PSH or Tcp.ACK, data, null)
        flow.sndNxt += data.size
    }

    private fun sendFin(flow: Flow) {
        if (flow.serverFinSent) return
        flow.unacked.addLast(Unacked(flow.sndNxt, ByteArray(0), true, clock()))
        emit(flow.key, flow.sndNxt, flow.rcvNxt, Tcp.FIN or Tcp.ACK, ByteArray(0), null)
        flow.sndNxt += 1
        flow.serverFinSent = true
    }

    private fun sendAck(flow: Flow) =
        emit(flow.key, flow.sndNxt, flow.rcvNxt, Tcp.ACK, ByteArray(0), null)

    private fun emit(key: Key, seq: Int, ack: Int, flags: Int, payload: ByteArray, mss: Int?) {
        val seg = Tcp.build(
            key.dstIp, Protocol.ESP_IP, key.dstPort, key.srcPort,
            seq, ack, flags, WINDOW, payload, mss,
        )
        sendIp(Ipv4.build(Ipv4.PROTO_TCP, key.dstIp, Protocol.ESP_IP, seg))
    }

    // ---- teardown ---------------------------------------------------------------------------

    /** Both directions closed and our FIN acknowledged: the flow is done. */
    private fun maybeFinish(flow: Flow) {
        if (flow.state != State.ESTABLISHED) return
        if (flow.clientFin && flow.serverFinSent && flow.unacked.isEmpty()) {
            discard(flow)
            // The writer closes the socket after it has flushed everything the ESP32 sent.
            if (flow.writerDone) flow.socket?.let(::closeQuietly)
        }
    }

    /** Immediate close; optionally tells the ESP32 with a RST. */
    private fun abort(flow: Flow, sendRst: Boolean) {
        if (flow.state == State.CLOSED) return
        if (sendRst && flow.state != State.CONNECTING) {
            emit(flow.key, flow.sndNxt, flow.rcvNxt, Tcp.RST or Tcp.ACK, ByteArray(0), null)
        }
        discard(flow)
        flow.socket?.let(::closeQuietly)
        flow.writeQueue.clear()
        flow.writeQueue.put(STOP)
    }

    private fun discard(flow: Flow) {
        flow.state = State.CLOSED
        flows.remove(flow.key, flow)
        flow.cond.signalAll()
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: IOException) { }
    }

    // ---- timers -------------------------------------------------------------------------------

    /** Retransmissions and timeouts. Call at least once per second. */
    fun tick() {
        val now = clock()
        for (flow in flows.values.toList()) {
            flow.lock.withLock {
                when {
                    flow.state == State.CLOSED -> Unit
                    now - flow.lastActive > idleTimeoutMs -> abort(flow, sendRst = true)
                    flow.state == State.CONNECTING ->
                        if (now - flow.createdAt > connectTimeoutMs) abort(flow, sendRst = true)
                    flow.state == State.SYN_RCVD ->
                        if (now - flow.synSentAt > backoff(flow.synRetries)) {
                            if (++flow.synRetries > MAX_RETRIES) abort(flow, sendRst = true) else sendSynAck(flow)
                        }
                    else -> retransmit(flow, now)
                }
            }
        }
    }

    private fun retransmit(flow: Flow, now: Long) {
        val u = flow.unacked.firstOrNull() ?: return
        if (now - u.sentAt <= backoff(u.retries)) return
        if (++u.retries > MAX_RETRIES) {
            abort(flow, sendRst = true)
            return
        }
        u.sentAt = now
        val flags = if (u.fin) Tcp.FIN or Tcp.ACK else Tcp.PSH or Tcp.ACK
        emit(flow.key, u.seq, flow.rcvNxt, flags, u.data, null)
    }

    private fun backoff(retries: Int): Long = rtoMs shl minOf(retries, 4)

    fun closeAll() {
        for (flow in flows.values.toList()) flow.lock.withLock { abort(flow, sendRst = false) }
    }

    private companion object {
        const val WINDOW = 65535
        const val MAX_RETRIES = 8
        val FIN_MARK = ByteArray(0)
        val STOP = ByteArray(0)

        // Sequence-number comparison with 32-bit wraparound.
        fun lt(a: Int, b: Int) = a - b < 0
        fun le(a: Int, b: Int) = a - b <= 0
    }
}
