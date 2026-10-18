# Security policy

## Known limitations (protocol v1)

Version 1 has **no encryption and no authentication**. Anyone who can join the ESP32 access point can send frames to it while the link is down, and can observe traffic on the AP.

Mitigations today:
- Use a strong WPA2/WPA3 AP password.
- The ESP32 accepts frames only from its current peer address after the handshake.

A shared-key signature per frame is planned (see [docs/roadmap.md](docs/roadmap.md)). Do not use v1 to carry sensitive data without TLS end to end.

## Reporting a vulnerability

Please report security issues privately using GitHub's "Report a vulnerability" (Security tab) instead of a public issue. Include steps to reproduce and the affected version.
