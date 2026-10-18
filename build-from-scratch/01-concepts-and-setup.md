# 1. Concepts and setup

## 1.1 Why this project is hard

Linux can forward and rewrite packets in the kernel (NAT). An Android app cannot. So the app must:
1. Receive raw IP packets from the ESP32.
2. For each connection, open a **real socket** on mobile data.
3. Rebuild reply packets (IP header, UDP/TCP header, checksums) and send them back.

You are building a tiny NAT router in user space.

## 1.2 What to learn first

Do these in order. Do not skip the exercises; they are exactly what you will code later.

| Topic | Learn | Exercise (2 h total each block) |
| --- | --- | --- |
| IPv4 header | 20 bytes: version/IHL, total length, TTL, protocol, checksum, src, dst | Hand-decode a hex packet from a Wireshark capture |
| Internet checksum | RFC 1071: sum 16-bit words, fold carries, invert | Write `checksum()` in Python, verify against a capture |
| UDP header | 8 bytes: ports, length, checksum; pseudo-header | Build a DNS query by hand and send it with a raw UDP socket |
| ICMP echo | Type 8 request / type 0 reply, checksum covers the ICMP part only | Write a function that turns a request into a reply |
| TCP basics | SYN/SYN-ACK/ACK, sequence numbers, FIN/RST (needed later) | Read a capture of one HTTP request |
| MTU / MSS | Why 1400 and 1360 | Calculate: 1400 - 20 IP - 20 TCP = 1360 |

**Python first.** Write the packet parser and builder in Python before Kotlin. You will use it as a test tool (`tools/rn_protocol.py` is the finished version).

## 1.3 Platform knowledge

**ESP32 side**
- ESP-IDF project structure, components, `idf.py`
- FreeRTOS tasks
- Wi-Fi SoftAP mode
- lwIP: `struct netif`, `netif_add`, the `output` callback, `tcpip_input`, `pbuf`, `tcpip_callback`, default netif

**Android side**
- Foreground services and notifications
- `ConnectivityManager.requestNetwork`, `NetworkRequest`, `Network.bindSocket`
- `DatagramSocket`, `Socket`, threads, timeouts
- JUnit for unit tests

Free references: ESP-IDF Programming Guide (Wi-Fi, lwIP), lwIP docs (`netif`, `pbuf`), Android developer docs for `ConnectivityManager`. Always check names against your installed versions.

## 1.4 Install tools

### ESP-IDF
1. Install ESP-IDF 5.x following Espressif's getting-started guide for your OS.
2. Check: `idf.py --version`.
3. Plug in the ESP32; find the serial port (`ls /dev/cu.*` on macOS).
4. Test: build and flash the `hello_world` example, then run `idf.py monitor`.

### Android
1. Install Android Studio.
2. Install an SDK (API 34) and enable **Developer options** and **USB debugging** on your phone.
3. Check: `adb devices` lists the phone.
4. Test: run an empty activity app on the phone.

### Helpers
- Python 3
- Wireshark (to inspect packets)
- Git

## 1.5 Create the repo skeleton

```
reverse-net/
  docs/
  esp32/components/phonenet/
  esp32/examples/basic/
  android/
  tools/
```

`git init`, commit an empty README. Done when: all tools run and hello-world works on both ESP32 and phone.
