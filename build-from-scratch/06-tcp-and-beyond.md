# 6. TCP, security and publishing

## 6.1 Choose a TCP strategy

| Option | Effort | Risk |
| --- | --- | --- |
| Write your own TCP state machine in Kotlin | days to weeks | subtle bugs (retransmit, window, FIN handling) |
| Reuse a user-space stack (gVisor netstack as in `tun2socks`, or lwIP) | days | integration work and licence check |

Learn the basics either way.

## 6.2 Own TCP: build it in these steps

Create `TcpNat` with one `TcpFlow` per connection, key (ESP32 port, destination IP, destination port). Write a unit test for each step with a fake ESP32 that sends crafted segments.

**T1. Parse and build TCP segments.** Header fields: ports, seq, ack, data offset, flags (SYN, ACK, FIN, RST, PSH), window, checksum with pseudo-header, options. Test against a Wireshark capture.

**T2. Handshake.** On SYN: open a cellular `Socket` to the destination (with a timeout, on a worker thread). On success send SYN-ACK with your own initial sequence number, `ack = their seq + 1`, and an MSS option of 1360. On failure send RST. Wait for the final ACK.

**T3. ESP32 to internet data.** For each data segment in sequence: write the payload to the socket, advance `ack`, send an ACK. Ignore or re-ACK duplicates and out-of-order segments.

**T4. Internet to ESP32 data.** A reader thread reads the socket, cuts it into segments of at most 1360 bytes, numbers them, sends with PSH/ACK. Keep unacknowledged segments in a list.

**T5. Reliability.** Resend the oldest unacknowledged segment after about 1 s without an ACK. Respect the ESP32's advertised window so you never send more than it can take. Stop reading from the socket while the window is full.

**T6. Closing.** FIN from ESP32: ACK it, shut down the socket's output, send your own FIN when the socket closes. RST from either side: close the other side immediately and delete the flow. Time out half-open flows.

**T7. Limits and cleanup.** Cap the number of flows, expire idle flows, close everything when the link drops.

**Test milestones:** an ESP32 plain HTTP GET (milestone 6), then an HTTPS request (milestone 7). For HTTPS, watch the ESP32 heap (TLS needs tens of KB).

**Reference solution:** [`TcpNat.kt`](../android/app/src/main/java/dev/reversenet/nat/TcpNat.kt), [`Tcp.kt`](../android/app/src/main/java/dev/reversenet/nat/Tcp.kt) and the design notes in [docs/tcp.md](../docs/tcp.md). Its tests (`TcpNatTest.kt`) show how to play the ESP32 against a loopback server; copy that idea for your own tests.

## 6.3 Security (protocol version 2)

Version 1 has no authentication. Plan this:
1. Pre-shared key stored on both sides.
2. Add an HMAC-SHA256 (truncated, e.g. 8 bytes) to every frame, plus a counter to prevent replay.
3. Version byte becomes 2; reject unsigned frames.
4. Optionally encrypt (ChaCha20-Poly1305).
5. Update `docs/protocol.md` first, then the code on both sides.

## 6.4 Polish before publishing

- [ ] Compile from a clean checkout (ESP32 and Android).
- [ ] Unit tests and CI green.
- [ ] README with a status table that is honest about what is tested.
- [ ] LICENSE, CONTRIBUTING, SECURITY.
- [ ] No passwords or personal data committed.
- [ ] Tag a release and attach a debug APK.

## 6.5 Publish

1. Create the GitHub repository (public).
2. `git remote add origin ...` and `git push -u origin main`.
3. Enable Issues and private vulnerability reporting.
4. Optionally publish the `phonenet` component to the ESP-IDF Component Registry.
5. Share it with a short demo video and a clear "experimental" label.
