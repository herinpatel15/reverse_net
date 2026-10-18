# reverse-net protocol v1

Single source of truth for the frame format. The C (`esp32/`), Kotlin (`android/`) and Python (`tools/pc_relay.py`) implementations must match this document.

## Frame

Every UDP datagram carries exactly one frame: a 3-byte header and a payload.

```
+----------+-----------------+----------------------+
| type (1) | length (2, BE)  | payload (length B)   |
+----------+-----------------+----------------------+
```

`length` must equal the datagram size minus 3. Frames that do not match are dropped.

| Type | Name | Payload | Sent by |
| --- | --- | --- | --- |
| `0x01` | HELLO | version (1 B), tunnel MTU (2 B, BE) | Android app |
| `0x02` | HELLO_ACK | version (1 B), tunnel MTU (2 B, BE) | ESP32 |
| `0x03` | KEEPALIVE | empty | Both |
| `0x10` | IP_PACKET | one raw IPv4 packet | Both |
| `0x7F` | CLOSE | empty | Both |

## Constants

| Name | Value |
| --- | --- |
| ESP32 AP address / UDP port | `192.168.4.1` : `5000` |
| Tunnel network | `10.99.0.0/24` |
| ESP32 tunnel address | `10.99.0.2` |
| Android tunnel address | `10.99.0.1` |
| Tunnel MTU | `1400` (largest datagram 1403 B) |
| TCP MSS offered by the app | `1360` |
| Protocol version | `1` |
| Keepalive interval | 5 s |
| Link timeout | 15 s |
| HELLO retry interval (app) | 2 s |
| UDP flow idle timeout (app) | 30 s |

Frames with an unknown version or type are dropped.

## Handshake and liveness

1. The phone joins the ESP32 Wi-Fi and the app sends HELLO to `192.168.4.1:5000`.
2. The ESP32 stores the sender's address as its peer and answers HELLO_ACK. From then on it sends IP_PACKET frames to that peer.
3. Both sides send KEEPALIVE every 5 s. A side that hears nothing for 15 s marks the link down. The ESP32 then waits for a new HELLO; the app resends HELLO every 2 s.
4. The ESP32 accepts frames only from its current peer address, so a second device on the AP cannot inject packets once the link is up.
5. A new HELLO from the current peer's IP, even from a new UDP port (app restarted), re-acknowledges it and replaces the stored peer. A HELLO from another IP is ignored until the link is down.

## Security

Version 1 has no encryption or authentication. See [SECURITY.md](../SECURITY.md) and the [roadmap](roadmap.md).
