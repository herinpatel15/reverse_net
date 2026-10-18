package dev.reversenet

import dev.reversenet.nat.IcmpHandler
import dev.reversenet.nat.Ipv4
import dev.reversenet.nat.Udp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4Test {
    private val esp = Protocol.ESP_IP
    private val gw = Protocol.ANDROID_IP

    @Test fun buildThenParseRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val raw = Ipv4.build(Ipv4.PROTO_UDP, esp, gw, payload)
        val pkt = Ipv4.parse(raw, raw.size)!!
        assertEquals(Ipv4.PROTO_UDP, pkt.protocol)
        assertEquals(esp, pkt.src)
        assertEquals(gw, pkt.dst)
        assertArrayEquals(payload, pkt.payload())
    }

    @Test fun badHeaderChecksumRejected() {
        val raw = Ipv4.build(Ipv4.PROTO_UDP, esp, gw, byteArrayOf(0))
        raw[10] = (raw[10] + 1).toByte()
        assertNull(Ipv4.parse(raw, raw.size))
    }

    @Test fun truncatedRejected() {
        val raw = Ipv4.build(Ipv4.PROTO_UDP, esp, gw, ByteArray(10))
        assertNull(Ipv4.parse(raw, raw.size - 1))
    }

    @Test fun fragmentsRejected() {
        val raw = Ipv4.build(Ipv4.PROTO_UDP, esp, gw, ByteArray(10))
        Ipv4.writeU16(raw, 6, 0x2000) // MF
        Ipv4.writeU16(raw, 10, 0)
        Ipv4.writeU16(raw, 10, dev.reversenet.nat.Checksum.compute(raw, 0, 20))
        assertNull(Ipv4.parse(raw, raw.size))
    }

    @Test fun udpRoundTripWithChecksum() {
        val seg = Udp.build(esp, gw, 1234, 53, "hello".toByteArray())
        val raw = Ipv4.build(Ipv4.PROTO_UDP, esp, gw, seg)
        val d = Udp.parse(Ipv4.parse(raw, raw.size)!!)!!
        assertEquals(1234, d.srcPort)
        assertEquals(53, d.dstPort)
        assertEquals("hello", String(d.payload))
    }

    @Test fun udpBadChecksumRejected() {
        val seg = Udp.build(esp, gw, 1234, 53, "hello".toByteArray())
        seg[8] = (seg[8] + 1).toByte() // corrupt payload
        val raw = Ipv4.build(Ipv4.PROTO_UDP, esp, gw, seg)
        assertNull(Udp.parse(Ipv4.parse(raw, raw.size)!!))
    }

    @Test fun icmpEchoReply() {
        val icmp = byteArrayOf(8, 0, 0, 0, 0x12, 0x34, 0x00, 0x07, 9, 8, 7)
        Ipv4.writeU16(icmp, 2, dev.reversenet.nat.Checksum.compute(icmp, 0, icmp.size))
        val raw = Ipv4.build(Ipv4.PROTO_ICMP, esp, gw, icmp)
        val reply = IcmpHandler.reply(Ipv4.parse(raw, raw.size)!!)!!
        val rp = Ipv4.parse(reply, reply.size)!!
        assertEquals(gw, rp.src)
        assertEquals(esp, rp.dst)
        val body = rp.payload()
        assertEquals(0, body[0].toInt())
        assertEquals(0, dev.reversenet.nat.Checksum.compute(body, 0, body.size))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0, 7, 9, 8, 7), body.copyOfRange(4, body.size))
    }

    @Test fun icmpToOtherHostIgnored() {
        val icmp = byteArrayOf(8, 0, 0, 0, 0, 1, 0, 1)
        Ipv4.writeU16(icmp, 2, dev.reversenet.nat.Checksum.compute(icmp, 0, icmp.size))
        val raw = Ipv4.build(Ipv4.PROTO_ICMP, esp, Protocol.ip(8, 8, 8, 8), icmp)
        assertNull(IcmpHandler.reply(Ipv4.parse(raw, raw.size)!!))
    }
}
