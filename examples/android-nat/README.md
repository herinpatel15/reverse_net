# Example: using the packet and NAT classes

The Android app is in [`android/`](../../android). Its core classes are plain Kotlin with no `android.*` imports, so you can reuse them in a JVM program or unit test.

## Parse an IPv4 packet and check its checksum

```kotlin
import dev.reversenet.nat.Ipv4
import dev.reversenet.nat.Udp

fun inspect(raw: ByteArray) {
    // Ipv4.parse returns null for malformed, fragmented, or bad-checksum packets.
    val ip = Ipv4.parse(raw, raw.size) ?: return println("rejected")
    println("${Ipv4.toString(ip.src)} -> ${Ipv4.toString(ip.dst)} protocol=${ip.protocol}")

    if (ip.protocol == Ipv4.PROTO_UDP) {
        val udp = Udp.parse(ip) ?: return println("bad UDP checksum")
        println("UDP ${udp.srcPort} -> ${udp.dstPort}, ${udp.payload.size} bytes")
    }
}
```

## Build a packet

```kotlin
import dev.reversenet.Protocol
import dev.reversenet.nat.Ipv4
import dev.reversenet.nat.Udp

val payload = "hello".toByteArray()
val segment = Udp.build(Protocol.ESP_IP, Protocol.ip(8, 8, 8, 8), 40000, 53, payload)
val packet = Ipv4.build(Ipv4.PROTO_UDP, Protocol.ESP_IP, Protocol.ip(8, 8, 8, 8), segment)
// `packet` is a complete IPv4 packet with correct checksums.
```

## Wire the NAT together

`PacketDispatcher` is the entry point for every IP packet from the ESP32. Give it two NAT objects and a function that sends bytes back to the ESP32:

```kotlin
val linkSend: (ByteArray) -> Unit = { packet -> linkLoop.sendIp(packet) }

val udp = UdpNat(
    socketFactory = { networks.newCellularUdpSocket() },   // a socket bound to mobile data
    sendIp = linkSend,
)
val tcp = TcpNat(
    connector = { ip, port -> networks.connectCellularTcp(ip, port) },
    sendIp = linkSend,
)
val dispatcher = PacketDispatcher(udp, tcp, linkSend)

// called by LinkLoop for every IP_PACKET frame
linkLoop = LinkLoop(socket, esp, onIp = dispatcher::handleIp, onState = { up -> /* update UI */ }, onTick = {
    udp.expire()
    tcp.tick()
})
```

`RelayService` in [`android/app/src/main/java/dev/reversenet/RelayService.kt`](../../android/app/src/main/java/dev/reversenet/RelayService.kt) does exactly this, with the foreground service and notification around it.

## Tests

Run the unit tests (they use a fake ESP32 and a loopback server, so no phone is needed):

```sh
cd android
./gradlew test
```

Read `TcpNatTest.kt` for an example of driving the TCP NAT from a test.
