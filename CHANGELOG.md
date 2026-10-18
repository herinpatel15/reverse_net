# Changelog

All notable changes are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [0.2.0] - 2026-10-18

### Added
- **TCP NAT** in the Android app (`TcpNat`, `Tcp`): handshake, bidirectional data, window-limited sending, retransmission, graceful and abortive close. Plain HTTP and HTTPS from the ESP32 now have a path through the phone. Design notes in `docs/tcp.md`.
- 11 TCP tests against a real loopback server (28 unit tests in total).
- HTTPS fetch in the ESP32 example, with `sdkconfig.defaults` (larger main stack, certificate bundle).
- Gradle wrapper (Gradle 8.9) and a tag-triggered release workflow that attaches a debug APK.

### Changed
- CI uses the Gradle wrapper.
- README status table now distinguishes automated-test coverage from hardware verification.

## [0.1.0] - 2026-10-18

### Added
- Protocol v1 specification (`docs/protocol.md`).
- ESP-IDF component `phonenet` and a basic example.
- Android relay app: link loop, IPv4 parser, ICMP echo, UDP/DNS NAT.
- `tools/pc_relay.py` reference relay and `tools/frame_dump.py`.

### Not yet implemented
- Frame authentication / encryption (protocol v2), IPv6.
