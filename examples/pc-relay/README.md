# Example: test the ESP32 with a computer instead of a phone

`tools/pc_relay.py` speaks the same protocol as the Android app. It answers ping to `10.99.0.1`, so you can check the ESP32 side before you have the app working. It does not forward TCP or UDP to the internet, so use it for handshake and ping tests only.

## Steps

1. Flash the ESP32 with the phonenet example (or your own app that calls `phonenet_start()`).
2. On your computer, join the ESP32 Wi-Fi network (`reverse-net` by default; the password is set in `menuconfig`).
3. From the repository root, run:

   ```sh
   python3 tools/pc_relay.py --esp 192.168.4.1
   ```

4. Expected output on the computer:

   ```
   link up
   ip proto=1 10.99.0.2 -> 10.99.0.1 len=...
   answered ping
   ```

5. Expected output on the ESP32 serial monitor: `link up`.

Stop the script with Ctrl+C. The ESP32 then logs `link down` after 15 seconds.

## Use the protocol helpers in your own script

`tools/rn_protocol.py` has the frame and IPv4 helpers. For example, to build a HELLO frame and check it:

```python
import sys
sys.path.insert(0, "tools")
import rn_protocol as p

hello = p.hello()
assert hello == bytes([0x01, 0x00, 0x03, 0x01, 0x05, 0x78])

ftype, payload = p.parse(hello)
print("type", hex(ftype), "payload", payload.hex())  # type 0x1 payload 010578
```

## Run the tests

```sh
python3 -m unittest discover -s tools -p "test_*.py" -v
```
