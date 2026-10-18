# 5. Integrate and test

Order matters. Test one layer at a time, bottom to top.

## 5.1 Work in this order

| Phase | ESP32 steps | Android steps | Test partner |
| --- | --- | --- | --- |
| Protocol | E3-E5 | A2-A3 | `tools/pc_relay.py` or a Python fake ESP32 |
| Ping | E6-E10 | A5-A6 | each other |
| Networks | | A7 | `adb shell dumpsys connectivity` |
| DNS | E8 | A8 | `getaddrinfo` on the ESP32 |
| Stability | E11 | A9 | reboot each side |

You can finish the ESP32 side with no phone at all by using `pc_relay.py`, and finish the Android protocol side with no ESP32 by using a Python or JUnit fake. Do that: it cuts debugging time a lot.

## 5.2 Milestone checklist

1. **AP reachable.** Phone joins the ESP32 Wi-Fi; `ping 192.168.4.1` from a computer on the AP works.
2. **Handshake.** ESP32 log: `link up`. App notification: link up.
3. **Both networks.** Notification shows Wi-Fi yes and cellular yes.
4. **Ping.** ESP32 pings `10.99.0.1`; app logs the echo request and answers.
5. **DNS.** ESP32 resolves a hostname; app logs a UDP flow to `8.8.8.8:53`.
6. **HTTP.** Needs TCP ([06](06-tcp-and-beyond.md)).
7. **HTTPS.** Same, plus enough heap on the ESP32.
8. **Reconnect.** Reboot ESP32, then restart app; both recover without manual steps.

## 5.3 Debug with Wireshark

1. Turn on the app's debug packet dump.
2. `adb logcat -s ReverseNet > log.txt`
3. `python3 tools/frame_dump.py < log.txt > dump.txt`
4. Wireshark: File, Import from Hex Dump, encapsulation **Raw IP**.
5. Wireshark flags bad IP, UDP and TCP checksums in red/black.

On a computer joined to the ESP32 AP you can also capture the tunnel UDP port 5000 directly.

## 5.4 Symptom table

| Symptom | Likely cause |
| --- | --- |
| No `link up` | Phone not on the ESP32 AP; control socket not bound to the Wi-Fi `Network`; port or address wrong |
| `link up` then `link down` after 15 s | One side not sending KEEPALIVE, or timers using the wrong clock |
| Ping gets no reply | Wrong checksum in the ICMP reply; src/dst not swapped; reply not sent through `sendIp` |
| DNS fails | Missing cellular network; reply checksum or addresses wrong; flow expired too early |
| Android drops the ESP32 Wi-Fi | Accept "stay connected"; keep the Wi-Fi network request active |
| Big transfers stall | MTU too large; lower to 1300 and MSS to 1260 on both sides |
| ESP32 resets | Stack overflow in a task (raise from 4096); big buffers on the stack; heap exhausted |

## 5.5 Before moving on

- Commit and tag `v0.1.0-udp`.
- Write the working versions of each milestone's test into the README status table with phone model, Android version and ESP32 variant.
