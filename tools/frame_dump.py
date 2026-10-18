#!/usr/bin/env python3
"""Turn the app's hex packet dump into Wireshark input.

Reads lines containing a hex-encoded IPv4 packet (as logged by 'Debug packet dump') and writes a
text2pcap-compatible hex dump:   python3 frame_dump.py < logcat.txt | text2pcap -e 0x800 - out.pcap
Or in Wireshark: File > Import from Hex Dump, encapsulation 'Raw IP'.
"""
import re
import sys

HEX = re.compile(r"\b((?:[0-9a-fA-F]{2}){20,})\s*$")


def to_dump(hexstr: str) -> str:
    data = bytes.fromhex(hexstr)
    lines = []
    for off in range(0, len(data), 16):
        chunk = data[off:off + 16]
        lines.append(f"{off:06x} " + " ".join(f"{b:02x}" for b in chunk))
    return "\n".join(lines)


def main():
    for line in sys.stdin:
        m = HEX.search(line.strip())
        if m:
            print(to_dump(m.group(1)))
            print()


if __name__ == "__main__":
    main()
