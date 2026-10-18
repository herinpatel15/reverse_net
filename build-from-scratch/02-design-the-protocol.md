# 2. Design the protocol

Write this down **before any code**. Both sides must agree byte for byte. Save your decisions in `docs/protocol.md`. (A finished version is in the repo: [docs/protocol.md](../docs/protocol.md).)

## 2.1 Transport

Use UDP between phone and ESP32:
- Simple, no connection state on the ESP32.
- One datagram = one frame, so no stream splitting to get wrong.
- ESP32 listens on `192.168.4.1:5000` (the default SoftAP address).

## 2.2 Frame format

```
+----------+-----------------+----------------------+
| type (1) | length (2, BE)  | payload (length B)   |
+----------+-----------------+----------------------+
```

Rules: big-endian length; `length == datagram size - 3`; drop anything that does not match.

## 2.3 Message types

| Type | Name | Payload | From |
| --- | --- | --- | --- |
| `0x01` | HELLO | version (1), MTU (2) | Android |
| `0x02` | HELLO_ACK | version (1), MTU (2) | ESP32 |
| `0x03` | KEEPALIVE | none | both |
| `0x10` | IP_PACKET | raw IPv4 packet | both |
| `0x7F` | CLOSE | none | both |

## 2.4 Addresses and constants

Decide and write down:
- Tunnel network `10.99.0.0/24`, ESP32 `10.99.0.2`, Android `10.99.0.1` (the ESP32's gateway).
- MTU 1400. Must be smaller than the Wi-Fi MTU (1500) minus IP (20) and UDP (8) and your 3-byte header: 1400 + 3 + 28 = 1431. Fits.
- Keepalive 5 s, timeout 15 s, HELLO retry 2 s.

## 2.5 State machine (draw it)

```
DOWN --(HELLO received / ACK received)--> UP
UP   --(15 s silence or CLOSE)----------> DOWN
```

- ESP32 in DOWN: waits for HELLO. In UP: accepts frames only from the stored peer.
- Android in DOWN: sends HELLO every 2 s. In UP: sends KEEPALIVE every 5 s.

## 2.6 Exercise: prove the format in Python

Write `tools/rn_protocol.py` with `frame()`, `parse()`, `hello()`. Test:
- `hello()` equals bytes `01 00 03 01 05 78`.
- `parse()` rejects a frame whose length byte is wrong.

Done when your Python unit tests pass. You will reuse these as the oracle for C and Kotlin: when a C or Kotlin output looks wrong, compare it with the Python output for the same input.

## 2.7 Known weakness

Version 1 has no encryption or authentication. Write that in the doc; plan a shared-key signature for version 2 ([06-tcp-and-beyond.md](06-tcp-and-beyond.md)).
