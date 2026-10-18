# Contributing

Thanks for helping! This project is early and hardware-dependent, so test reports are as useful as code.

## Reporting problems

Open an issue and include:
- ESP32 variant and ESP-IDF version
- Phone model and Android version
- ESP32 serial log and `adb logcat` output (filter on `ReverseNet`)
- For packet problems: a hex dump of the frame (see `tools/frame_dump.py`)

## Development

- **Protocol changes** must update [`docs/protocol.md`](docs/protocol.md) first, and both implementations (C and Kotlin) plus `tools/pc_relay.py` in the same PR. Bump the protocol version for incompatible changes.
- **ESP32:** `cd esp32/examples/basic && idf.py build`
- **Android:** `cd android && ./gradlew test assembleDebug`
- **Python:** `python3 -m unittest discover -s tools -p "test_*.py"`

## Pull requests

- Keep PRs focused; describe how you tested (hardware or not).
- Match the surrounding style (`.editorconfig`).
- By contributing you agree your work is licensed under the MIT License.
