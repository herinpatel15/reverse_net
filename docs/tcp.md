# TCP NAT design

Implemented in [`TcpNat.kt`](../android/app/src/main/java/dev/reversenet/nat/TcpNat.kt) and [`Tcp.kt`](../android/app/src/main/java/dev/reversenet/nat/Tcp.kt). Pure JVM code (no `android.*`), covered by `TcpNatTest`.

The ESP32 speaks TCP to the app as if the app were the remote server. For each connection the app opens a real socket on the cellular network and translates between the two.

## Flow identity

One flow per `(ESP32 source port, destination IP, destination port)`. At most 64 flows at once; extra SYNs get a RST.

## State machine (per flow)

```
 SYN from ESP32
      |
 CONNECTING --connect fails--> RST|ACK to ESP32, flow removed
      | connect ok: send SYN-ACK (MSS 1360)
 SYN_RCVD --(ACK of our SYN)--> ESTABLISHED --(both FINs done, our FIN acked)--> CLOSED
      \__ SYN retransmit: resend SYN-ACK        \__ RST either way / error / timeout --> CLOSED
```

## Directions

**ESP32 -> internet.** In-order data is queued to a writer thread (`socket.write`) and ACKed at once. Segments already received are trimmed; gaps get a duplicate ACK and are dropped (the ESP32 retransmits). ESP32 FIN: ACK it, then `shutdownOutput()` after the queued data is flushed.

**Internet -> ESP32.** A reader thread reads at most `min(MSS, ESP32 window - bytes in flight)` bytes, wraps them in a segment and keeps a copy until ACKed. EOF from the server becomes a FIN. While the ESP32 window is full the reader waits and the kernel socket buffer applies back-pressure to the server.

## Reliability

- Retransmit the oldest unacknowledged segment after 1 s, doubling up to 16 s; after 8 retries the flow is reset.
- SYN-ACK is retransmitted the same way.
- Idle flows are reset after 5 minutes. `tick()` runs from the link loop (at least once per second).
- Sequence comparisons use 32-bit wraparound arithmetic.

## Deliberate simplifications

| Simplification | Consequence |
| --- | --- |
| No out-of-order reassembly | Packet loss costs a retransmit; Wi-Fi in a short link is mostly in order |
| Fixed advertised window of 65535, no window scaling | ESP32 sends at most 64 KB before an ACK; fine for an ESP32 |
| No congestion control | The link is one hop; the ESP32 is the slow side |
| No fast retransmit, no SACK, no timestamps | Slower recovery from loss |
| Only the MSS option is parsed | Window scale / SACK offered by the ESP32 are ignored (we do not echo them, so they stay disabled) |
| Unbounded write queue per flow | A very slow cellular uplink can buffer up to what the ESP32 had in flight (<= 64 KB) |
| No TIME_WAIT | A late retransmitted segment of a finished connection gets a RST |
| IPv4 only, no fragments | The ESP32 never fragments at MTU 1400 |

## Tests

`TcpNatTest` plays the ESP32 against a real loopback server: handshake, data both ways, ESP32-first and server-first close, refused connections, stray segments, duplicate/out-of-order data, retransmission (injected clock), window limit, RST, and SYN retransmit.
