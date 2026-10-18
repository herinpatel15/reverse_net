# Build reverse-net from scratch (no AI)

A step-by-step developer guide. You write every line yourself, test after each step, and only look at the finished code in this repo when you are stuck.

## The goal

An ESP32 that reaches the internet through your own Android app:

```
ESP32 app -> lwIP netif "pn" -> UDP frames over ESP32 Wi-Fi -> Android app -> mobile data -> internet
```

## How to use this guide

Read in this order. ESP32 and Android are separate tracks; do the protocol first, then work on both tracks side by side.

| # | File | What you do |
| --- | --- | --- |
| 1 | [01-concepts-and-setup.md](01-concepts-and-setup.md) | Learn the needed networking, install the tools |
| 2 | [02-design-the-protocol.md](02-design-the-protocol.md) | Write the frame format on paper first |
| 3 | [03-esp32-step-by-step.md](03-esp32-step-by-step.md) | Build the ESP32 library (C, ESP-IDF) |
| 4 | [04-android-step-by-step.md](04-android-step-by-step.md) | Build the Android relay app (Kotlin) |
| 5 | [05-integrate-and-test.md](05-integrate-and-test.md) | Connect both, debug with Wireshark |
| 6 | [06-tcp-and-beyond.md](06-tcp-and-beyond.md) | TCP/HTTPS, security, publishing |

## Rules that save days

1. **One step, one test.** Never start a step until the previous test passes.
2. **Log everything** on both sides (ESP32 serial, `adb logcat`). Most bugs are visible in a log.
3. **Hex-dump packets** when something is wrong; Wireshark does not lie.
4. **Keep the protocol document and both code bases identical.** A change on one side without the other is the number one source of confusion.
5. **Commit after every passing test.**

## Milestones (same as the repo README)

1. ESP32 Wi-Fi AP reachable from the phone
2. HELLO / `link up`
3. App holds Wi-Fi and cellular networks
4. ESP32 pings `10.99.0.1`
5. DNS works
6. HTTP works (needs TCP)
7. HTTPS works
8. Auto-reconnect

## Time estimate

About 2 days for milestones 1 to 5, and several more days for TCP (milestone 6).

## Reference solution

Finished code for every step lives in the repo:
- ESP32: [`esp32/components/phonenet`](../esp32/components/phonenet)
- Android: [`android/app/src/main/java/dev/reversenet`](../android/app/src/main/java/dev/reversenet)
- PC test relay: [`tools/pc_relay.py`](../tools/pc_relay.py)

Try each step alone first. Compare only after your own test passes or after 1 hour of being stuck.
