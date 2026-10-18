#!/usr/bin/env python3
"""Reference reverse-net relay for a PC: speaks protocol v1 to the ESP32 and answers pings
to 10.99.0.1. Use it to test the ESP32 side without a phone. UDP/TCP traffic is only logged.

Join the ESP32 access point from this computer, then run:  python3 tools/pc_relay.py
"""
import argparse
import socket
import sys
import time

import rn_protocol as p


class Relay:
    def __init__(self, sock: socket.socket, esp, log=print):
        self.sock, self.esp, self.log = sock, esp, log
        self.up = False
        self.stopped = False

    def send(self, data: bytes):
        self.sock.sendto(data, self.esp)

    def step(self, now: float, state: dict):
        """One loop iteration: timers, then handle at most one datagram."""
        if self.up and now - state["last_rx"] > p.TIMEOUT_S:
            self.up = False
            self.log("link down")
        if not self.up and now - state["last_hello"] > p.HELLO_RETRY_S:
            self.send(p.hello())
            state["last_hello"] = now
        if self.up and now - state["last_ka"] > p.KEEPALIVE_S:
            self.send(p.frame(p.T_KEEPALIVE))
            state["last_ka"] = now
        try:
            data, addr = self.sock.recvfrom(2048)
        except socket.timeout:
            return
        if addr != self.esp:
            return
        parsed = p.parse(data)
        if parsed is None:
            return
        state["last_rx"] = time.monotonic()
        ftype, payload = parsed
        if ftype == p.T_HELLO_ACK and payload[:1] == bytes([p.VERSION]):
            if not self.up:
                self.log("link up")
            self.up = True
            state["last_ka"] = state["last_rx"]
        elif ftype == p.T_CLOSE:
            self.up = False
            self.log("link closed by ESP32")
        elif ftype == p.T_IP and self.up:
            self.handle_ip(payload)

    def handle_ip(self, raw: bytes):
        ip = p.parse_ipv4(raw)
        if ip is None:
            self.log(f"dropped malformed IPv4 packet ({len(raw)} B)")
            return
        src, dst = ".".join(map(str, ip["src"])), ".".join(map(str, ip["dst"]))
        self.log(f"ip proto={ip['proto']} {src} -> {dst} len={len(raw)}")
        reply = p.icmp_echo_reply(ip)
        if reply:
            self.send(p.frame(p.T_IP, reply))
            self.log("answered ping")

    def run(self):
        self.sock.settimeout(1.0)
        now = time.monotonic()
        state = {"last_rx": now, "last_hello": now - p.HELLO_RETRY_S - 1, "last_ka": now}
        while not self.stopped:
            self.step(time.monotonic(), state)
        if self.up:
            self.send(p.frame(p.T_CLOSE))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--esp", default="192.168.4.1", help="ESP32 AP address")
    ap.add_argument("--port", type=int, default=p.ESP_PORT)
    args = ap.parse_args()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 0))
    relay = Relay(sock, (args.esp, args.port))
    try:
        relay.run()
    except KeyboardInterrupt:
        relay.stopped = True
        sys.exit(0)


if __name__ == "__main__":
    main()
