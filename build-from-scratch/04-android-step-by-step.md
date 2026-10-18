# 4. Android track: step by step

Language: Kotlin. Final result: a foreground-service app that joins the ESP32 link and relays its traffic over mobile data.

Reference solution: [`android/app/src/main/java/dev/reversenet`](../android/app/src/main/java/dev/reversenet). Do not open it until your step's test has passed or you have been stuck for an hour.

**Design rule:** keep protocol and NAT code free of `android.*` imports (plain Kotlin/JVM). Then you can unit-test it on your computer in seconds, without a phone.

---

## Step A1: Project and permissions

1. New Android Studio project (Empty Activity), package `dev.reversenet`, min SDK 26.
2. Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.
3. Add JUnit to `testImplementation`.

**Test:** the app installs and opens.

---

## Step A2: Protocol and framing (pure Kotlin, unit-tested)

1. `Protocol` object: constants from `docs/protocol.md`; `frame(type, payload)` using `ByteBuffer`; `parse(data, len)` returning `null` on bad length; `hello()`.
2. Watch signed bytes: `data[0].toInt() and 0xFF`.
3. Unit tests:
   - `hello()` bytes equal `01 00 03 01 05 78`.
   - round trip of a frame
   - length mismatch rejected

**Test:** `./gradlew test` passes. Cross-check output against your Python `rn_protocol.py`.

---

## Step A3: Control socket and link loop

**Goal:** the app sends HELLO and reaches `up`.

Write `LinkLoop` with a plain `DatagramSocket` for now (no network binding yet):
1. Fields: `up`, timestamps `lastRx`, `lastHello`, `lastKa`.
2. Loop with `soTimeout = 1000`: if down and 2 s passed send HELLO; if up and 5 s passed send KEEPALIVE; if up and 15 s silent go down.
3. Ignore datagrams not from `192.168.4.1:5000`.
4. On HELLO_ACK (version 1) set `up = true`; on CLOSE set `up = false`; on IP_PACKET call a callback.
5. Use `System.nanoTime()` or `elapsedRealtime()` (never wall-clock time) for timers.
6. Provide `sendIp(packet)` and `stop()` (sends CLOSE).

**Unit test without hardware:** start a fake ESP32 as another `DatagramSocket` on loopback; assert it receives HELLO, reply with HELLO_ACK, assert `up` becomes true. (See `LinkLoopTest` in the repo for the idea.)

**Hardware test:** with `pc_relay.py` playing the other side this also works from a computer. On a real phone, run the loop on its own thread and watch the ESP32 print `link up`.

---

## Step A4: Foreground service

**Goal:** the loop keeps running with the screen off.

1. `RelayService : Service`. Create a notification channel, call `startForeground` with the `connectedDevice` type on API 29+.
2. Start the loop thread in `onStartCommand`; stop it in `onDestroy`.
3. Simple `MainActivity` with Start/Stop buttons; request `POST_NOTIFICATIONS` on Android 13+.
4. Show status text in the notification: Wi-Fi yes/no, cellular yes/no, link up/down.

**Test:** start the service, lock the screen for 2 minutes; ESP32 still shows keepalive traffic and no `link down`.
**Common mistakes:** forgetting the service type in the manifest (crash on Android 14); network on the main thread.

---

## Step A5: Packet parser, builder, checksum (pure Kotlin)

1. `Checksum`: sum 16-bit words, handle odd length, fold carries, invert. Test with the RFC 1071 example (`00 01 f2 03 f4 f5 f6 f7` gives `0x220d`).
2. `Ipv4.parse(bytes)`: check version 4, IHL, total length, reject fragments (MF flag or offset), verify header checksum. Return an object with `protocol`, `src`, `dst`, `payload`.
3. `Ipv4.build(protocol, src, dst, payload)`: 20-byte header, DF flag, TTL 64, correct checksum.
4. Unit tests: build then parse; corrupt a byte and expect rejection.

**Test:** tests pass; compare one built packet with the Python builder byte for byte.

---

## Step A6: ICMP echo to 10.99.0.1

1. In the IP dispatcher: if protocol is 1 and destination is `10.99.0.1` and ICMP type is 8, build a reply: type 0, recompute the ICMP checksum (over the whole ICMP message), swap source and destination.
2. Send it with `linkLoop.sendIp`.
3. Unit-test with a hand-built echo request.

**Hardware test:** ESP32 ping to `10.99.0.1` succeeds. Also log every received packet's protocol, source, destination and length.

Commit: `android: link loop, ip parser, icmp`. **Milestone 4 done.**

---

## Step A7: Use both networks (Wi-Fi to ESP32, cellular to internet)

This is the Android-specific hard part.

1. `ConnectivityManager.requestNetwork` for **cellular** with `NET_CAPABILITY_INTERNET`; store the `Network` in `onAvailable`, clear in `onLost`.
2. A second request for **Wi-Fi** with the internet capability removed (the ESP32 network has no internet). Store it the same way.
3. Bind sockets: `wifi.bindSocket(controlSocket)`, `cellular.bindSocket(outgoingSocket)`.
4. Rebuild the link loop whenever the Wi-Fi `Network` changes.
5. Show both states in the notification.
6. When Android asks whether to stay connected to a Wi-Fi network with no internet, answer **yes**.

**Test:** `adb shell dumpsys connectivity` lists both networks. Turn mobile data off: `cellular` becomes null and the notification says so.
**Common mistakes:** binding after the socket is already connected; testing with Wi-Fi-only devices (no cellular exists); Android dropping the ESP32 Wi-Fi because it has no internet. Behaviour differs by Android version, so test on your real phone early.

---

## Step A8: UDP NAT and DNS

**Goal:** the ESP32 resolves a hostname.

1. Flow key: (ESP32 source port, destination IP, destination port). Keep a `ConcurrentHashMap<Key, Flow>`.
2. On a UDP packet from the ESP32: parse and verify the UDP checksum (0 means "none" in IPv4), find or create the flow.
3. Creating a flow: a cellular-bound `DatagramSocket` and a reader thread.
4. Send the payload to the real destination.
5. Reader thread: for each reply build a UDP segment with **swapped** addresses and ports, a correct pseudo-header checksum, wrap it in IPv4 (source = the real server, destination = `10.99.0.2`), send as IP_PACKET.
6. Drop replies larger than the tunnel MTU (1372 bytes of UDP payload).
7. Expire flows idle for 30 s: close the socket. Call this from the link loop tick.
8. Close all flows when the link goes down.

**Unit test:** a fake UDP server on loopback; send a packet through the NAT, answer it, assert the produced IP packet has swapped addresses and a valid checksum. Inject a clock so expiry is testable without waiting.

**Hardware test:** the ESP32 resolves a hostname (e.g. `getaddrinfo("example.com")`) and logs the IP.

Commit: `android: udp nat`. **Milestone 5 done.**

---

## Step A9: Debug switch and stability

1. A checkbox that logs every packet as hex (to paste into Wireshark).
2. Handle `IOException` everywhere; never let a flow thread kill the service.
3. Reconnect: if the socket fails, recreate it after 1 s; if the ESP32 reboots, HELLO retries re-establish the link on their own.
4. Stop cleanly: send CLOSE, close sockets, unregister network callbacks.

**Test (milestone 8):** reboot the ESP32 while the app runs; the link recovers without touching the app. Restart the app; the ESP32 accepts the new HELLO.

---

## Step A10: Android definition of done

- [ ] notification shows Wi-Fi, cellular and link state
- [ ] ping works, DNS works
- [ ] unit tests for checksum, IPv4, protocol, ICMP, UDP NAT, link loop
- [ ] works with screen off
- [ ] reconnects after either side restarts
- [ ] TCP: either log-and-drop with a clear message until you finish [06-tcp-and-beyond.md](06-tcp-and-beyond.md), or the TCP NAT from that guide
