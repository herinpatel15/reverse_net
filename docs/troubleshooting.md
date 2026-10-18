# Troubleshooting

Check one layer at a time, from the Wi-Fi link up to the internet. Log both sides: the ESP32 serial monitor and `adb logcat -s ReverseNet`.

| Problem | Check |
| --- | --- |
| App never reaches `link up` | Is the phone joined to the ESP32 AP? Look for HELLO in the ESP32 serial log, and confirm the control socket is bound to the Wi-Fi `Network`. |
| Link is up but the ESP32 gets no replies | Log every IP packet the app receives (protocol, source, destination, length). If packets arrive the tunnel works and the problem is the NAT. |
| Packets arrive but nothing leaves the phone | Check `cellular` is not null and sockets use `cellular.bindSocket`. `adb shell dumpsys connectivity` lists the active networks. |
| Android drops the ESP32 Wi-Fi | Accept "stay connected" when Android warns about no internet, and keep the Wi-Fi `requestNetwork` active. |
| DNS fails | Query `8.8.8.8` from the ESP32 and log the UDP flow. Check the reply has swapped addresses and a correct checksum. |
| Checksum errors | Dump the packet as hex and import into Wireshark (File → Import from Hex Dump); it flags bad IP/UDP/TCP checksums. `tools/frame_dump.py` converts logs to that format. |
| HTTP hangs after DNS works | Look for `tcp connect ... failed` in logcat (cellular missing or destination blocked). With the debug dump on, check the SYN reaches the app and a SYN-ACK goes back. |
| HTTPS fails but HTTP works | Free heap on the ESP32 (TLS needs ~40 KB), main task stack >= 8192 (`sdkconfig.defaults`), and a certificate bundle in the build. |
| Small requests work, big downloads stall | MTU problem. Lower `PN_MTU` (ESP32) and `MTU` (app) to 1300, and the TCP MSS to 1260. |
| ESP32 crashes or runs out of heap | Check `esp_get_free_heap_size()`, raise the `phonenet` task stack above 4096, review lwIP buffer settings in `menuconfig`. |

Enable **Debug packet dump** in the app to log every packet as hex.
