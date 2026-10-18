# Roadmap

## Done
- Protocol v1, ESP32 component, Android link loop
- ICMP echo, UDP/DNS NAT, TCP NAT ([design](tcp.md))

## Next: hardware verification (needs testers)
Run the milestones on real ESP32 + phone combinations and record results in the README. Expect fixes, especially in the lwIP calls and Android network selection.

## Security (protocol v2)
- Per-frame HMAC with a pre-shared key and a replay counter.
- Optional encryption (e.g. ChaCha20-Poly1305).
- Configurable key on both sides.

## TCP improvements
- Out-of-order reassembly and fast retransmit.
- Window scaling for faster downloads.
- Bounded write queue with back-pressure to the ESP32.

## Other ideas
- IPv6 / NAT64.
- Forward ICMP echo to the internet.
- Real UDP/TCP NAT in `tools/pc_relay.py` for desktop testing.
- Publish `phonenet` to the ESP-IDF Component Registry.
- iOS is not possible with this approach (no equivalent of binding a socket to cellular while on Wi-Fi without VPN APIs).
