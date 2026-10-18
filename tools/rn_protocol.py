"""reverse-net protocol v1 helpers. Keep identical to docs/protocol.md."""
import struct

VERSION = 1
T_HELLO, T_HELLO_ACK, T_KEEPALIVE, T_IP, T_CLOSE = 0x01, 0x02, 0x03, 0x10, 0x7F
MTU = 1400
ESP_PORT = 5000
ANDROID_IP = bytes([10, 99, 0, 1])
ESP_IP = bytes([10, 99, 0, 2])
KEEPALIVE_S, TIMEOUT_S, HELLO_RETRY_S = 5.0, 15.0, 2.0


def frame(ftype: int, payload: bytes = b"") -> bytes:
    return struct.pack(">BH", ftype, len(payload)) + payload


def parse(data: bytes):
    """Return (type, payload) or None if the frame is malformed."""
    if len(data) < 3:
        return None
    ftype, plen = struct.unpack(">BH", data[:3])
    if len(data) != 3 + plen:
        return None
    return ftype, data[3:]


def hello() -> bytes:
    return frame(T_HELLO, struct.pack(">BH", VERSION, MTU))


def checksum(data: bytes, initial: int = 0) -> int:
    s = initial
    if len(data) % 2:
        data += b"\x00"
    for i in range(0, len(data), 2):
        s += (data[i] << 8) | data[i + 1]
    while s >> 16:
        s = (s & 0xFFFF) + (s >> 16)
    return ~s & 0xFFFF


def build_ipv4(proto: int, src: bytes, dst: bytes, payload: bytes, ident: int = 0) -> bytes:
    hdr = struct.pack(">BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), ident, 0x4000, 64, proto, 0, src, dst)
    hdr = hdr[:10] + struct.pack(">H", checksum(hdr)) + hdr[12:]
    return hdr + payload


def parse_ipv4(data: bytes):
    """Return dict(proto, src, dst, payload) or None if invalid/fragmented."""
    if len(data) < 20 or data[0] >> 4 != 4:
        return None
    ihl = (data[0] & 0xF) * 4
    total = struct.unpack(">H", data[2:4])[0]
    if ihl < 20 or total < ihl or total > len(data):
        return None
    if struct.unpack(">H", data[6:8])[0] & 0x3FFF:
        return None
    if checksum(data[:ihl]) != 0:
        return None
    return {"proto": data[9], "src": data[12:16], "dst": data[16:20], "payload": data[ihl:total]}


def icmp_echo_reply(ip: dict):
    """Reply to an echo request addressed to 10.99.0.1, else None."""
    if ip["proto"] != 1 or ip["dst"] != ANDROID_IP:
        return None
    icmp = ip["payload"]
    if len(icmp) < 8 or icmp[0] != 8 or checksum(icmp) != 0:
        return None
    body = b"\x00\x00\x00\x00" + icmp[4:]
    body = body[:2] + struct.pack(">H", checksum(body)) + body[4:]
    return build_ipv4(1, ip["dst"], ip["src"], body)
