import socket
import struct
import threading
import time
import unittest

import pc_relay
import rn_protocol as p


class ProtocolTests(unittest.TestCase):
    def test_hello_bytes(self):
        self.assertEqual(p.hello(), bytes([0x01, 0x00, 0x03, 0x01, 0x05, 0x78]))

    def test_frame_length_mismatch(self):
        self.assertIsNone(p.parse(p.frame(p.T_IP, b"ab")[:-1]))

    def test_ipv4_roundtrip_and_bad_checksum(self):
        raw = p.build_ipv4(17, p.ESP_IP, p.ANDROID_IP, b"xyz")
        self.assertEqual(p.parse_ipv4(raw)["payload"], b"xyz")
        bad = raw[:10] + b"\x00\x00" + raw[12:]
        self.assertIsNone(p.parse_ipv4(bad))

    def test_icmp_reply(self):
        icmp = b"\x08\x00\x00\x00\x12\x34\x00\x07abc"
        icmp = icmp[:2] + struct.pack(">H", p.checksum(icmp)) + icmp[4:]
        ip = p.parse_ipv4(p.build_ipv4(1, p.ESP_IP, p.ANDROID_IP, icmp))
        reply = p.parse_ipv4(p.icmp_echo_reply(ip))
        self.assertEqual(reply["src"], p.ANDROID_IP)
        self.assertEqual(reply["dst"], p.ESP_IP)
        self.assertEqual(reply["payload"][0], 0)
        self.assertEqual(p.checksum(reply["payload"]), 0)
        self.assertEqual(reply["payload"][4:], b"\x12\x34\x00\x07abc")


class RelayLoopbackTest(unittest.TestCase):
    def test_handshake_and_ping(self):
        fake = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        fake.bind(("127.0.0.1", 0))
        fake.settimeout(5)
        app = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        app.bind(("127.0.0.1", 0))
        logs = []
        relay = pc_relay.Relay(app, fake.getsockname(), log=logs.append)
        t = threading.Thread(target=relay.run)
        t.start()
        try:
            data, addr = fake.recvfrom(2048)
            self.assertEqual(data, p.hello())
            fake.sendto(p.frame(p.T_HELLO_ACK, bytes([1, 0x05, 0x78])), addr)

            icmp = b"\x08\x00\x00\x00\x00\x01\x00\x01"
            icmp = icmp[:2] + struct.pack(">H", p.checksum(icmp)) + icmp[4:]
            ping = p.build_ipv4(1, p.ESP_IP, p.ANDROID_IP, icmp)
            time.sleep(0.2)
            fake.sendto(p.frame(p.T_IP, ping), addr)
            data, _ = fake.recvfrom(2048)
            ftype, payload = p.parse(data)
            self.assertEqual(ftype, p.T_IP)
            self.assertEqual(p.parse_ipv4(payload)["payload"][0], 0)
            self.assertIn("link up", logs)
        finally:
            relay.stopped = True
            t.join(5)
            fake.close()
            app.close()


if __name__ == "__main__":
    unittest.main()
