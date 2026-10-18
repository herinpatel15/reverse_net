# Examples

How to use reverse-net, one example per part of the system.

| Example | Part | What it shows |
| --- | --- | --- |
| [esp32-http](esp32-http/README.md) | ESP32 | Add `phonenet` to your own ESP-IDF project and make an HTTP/HTTPS request through the phone |
| [pc-relay](pc-relay/README.md) | Computer | Test the ESP32 side without an Android phone |
| [android-nat](android-nat/README.md) | Android | Use the packet parser, checksums and NAT classes from your own code |

The full, buildable ESP32 example is in [`esp32/examples/basic`](../esp32/examples/basic). The examples here explain how to use the library in your own code.

## The three pieces

1. **ESP32 library (`phonenet`)**: call `phonenet_start()` once after your Wi-Fi access point is running, then wait for `phonenet_is_up()`. After that, normal sockets, `esp_http_client`, and MQTT work as usual.
2. **Android app**: join the ESP32's Wi-Fi, start the relay, and accept "stay connected" when Android warns about no internet.
3. **Protocol**: one UDP frame format on port 5000, described in [docs/protocol.md](../docs/protocol.md). Any device that speaks it can act as the relay, which is how `pc-relay` works.

## Order to try things

1. Run `pc-relay` on a computer joined to the ESP32 AP. Confirm the ESP32 log shows `link up`.
2. Flash `esp32-http` with the phone (or `pc-relay`) connected. Confirm the HTTP and HTTPS responses.
3. Read `android-nat` to see how packets are parsed and answered.
