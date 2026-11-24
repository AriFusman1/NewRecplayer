#!/usr/bin/env python3
"""Writes three small, realistic demo captures (Ethernet, classic pcap) to try the player by hand.

Usage: python3 tools/gen_demo.py OUT_DIR

  tcp_server_demo.pcap  a client (10.0.0.5) talks to our service (10.0.0.10:6000).
                        Category mode TCP server, port 6000. The replayer connects to the target.
  tcp_client_demo.pcap  our service (10.0.0.10) connects to a source (10.0.0.20:7000) that sends it data.
                        Category mode TCP client, port 7000. The replayer listens; the target connects.
  udp_demo.pcap         a source (10.0.0.30) sends datagrams to our service (10.0.0.10:5000 and :5001).
                        Category mode UDP server, port 5000 (or 5001). The replayer sends datagrams.

Every message is one readable text line ("TCP-SERVER #003 ..."), so tools/peer.py shows what arrives.
Unlike tools/gen_samples.py (parser edge cases), these files are plain and accepted by libpcap.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_samples import ACK, FIN, PSH, SYN, eth, ipv4, tcp, udp, write_pcap  # noqa: E402

START = 1_795_514_400  # 24/11/2026 12:00:00 Israel time (10:00 UTC)
SERVICE = "10.0.0.10"


class Capture:
    """Frames with timestamps in seconds from START."""

    def __init__(self):
        self.frames = []

    def add(self, seconds, frame):
        ns = int(round(seconds * 1_000_000_000))
        self.frames.append(((START + ns // 1_000_000_000, ns % 1_000_000_000 // 1000), frame, None))

    def write(self, path):
        write_pcap(path, sorted(self.frames, key=lambda f: f[0]), 1)


class TcpConnection:
    """One TCP connection with correct sequence numbers. 'a' opened it, 'b' accepted it."""

    def __init__(self, capture, a, b):
        self.capture = capture
        self.ends = {"a": a, "b": b}  # name -> (ip, port)
        self.seq = {"a": 1000, "b": 5000}

    def _send(self, t, sender, flags, data=b""):
        receiver = "b" if sender == "a" else "a"
        (sip, sport), (dip, dport) = self.ends[sender], self.ends[receiver]
        segment = tcp(sport, dport, self.seq[sender], self.seq[receiver], flags, data)
        self.capture.add(t, eth(ipv4(sip, dip, 6, segment)))
        self.seq[sender] += len(data) + (1 if flags & (SYN | FIN) else 0)

    def open(self, t):
        self._send(t, "a", SYN)
        self._send(t + 0.0004, "b", SYN | ACK)
        self._send(t + 0.0008, "a", ACK)

    def data(self, t, sender, text):
        self._send(t, sender, ACK | PSH, text.encode())
        self._send(t + 0.0003, "b" if sender == "a" else "a", ACK)

    def close(self, t):
        self._send(t, "a", FIN | ACK)
        self._send(t + 0.0004, "b", FIN | ACK)
        self._send(t + 0.0008, "a", ACK)


def tcp_server_demo(path):
    capture = Capture()
    connection = TcpConnection(capture, ("10.0.0.5", 51000), (SERVICE, 6000))
    connection.open(0.5)
    for i in range(1, 21):
        t = 1.0 + i * 0.4 + (0.6 if i > 10 else 0)  # a short pause in the middle
        connection.data(t, "a", f"TCP-SERVER #{i:03d} request from client: get item {i}\n")
        connection.data(t + 0.05, "b", f"OK item {i}\n")  # the service's replies are not replayed
    connection.close(11.0)
    capture.write(path)


def tcp_client_demo(path):
    capture = Capture()
    connection = TcpConnection(capture, (SERVICE, 52000), ("10.0.0.20", 7000))
    connection.open(0.5)
    connection.data(0.8, "a", "HELLO from service\n")  # the service's own message is not replayed
    for i in range(1, 16):
        connection.data(1.0 + i * 0.5, "b", f"TCP-CLIENT #{i:03d} update pushed by source, value={i * 7}\n")
    connection.close(9.5)
    capture.write(path)


def udp_demo(path):
    capture = Capture()
    source = "10.0.0.30"
    for i in range(1, 31):
        text = f"UDP #{i:03d} telemetry to port 5000, reading={100 + i}\n"
        capture.add(0.5 + i * 0.25, eth(ipv4(source, SERVICE, 17, udp(40000, 5000, text.encode()), ident=i)))
    for i in range(1, 11):
        text = f"UDP #{i:03d} status to port 5001\n"
        capture.add(0.6 + i * 0.7, eth(ipv4(source, SERVICE, 17, udp(40001, 5001, text.encode()), ident=100 + i)))
    capture.write(path)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    tcp_server_demo(os.path.join(out, "tcp_server_demo.pcap"))
    tcp_client_demo(os.path.join(out, "tcp_client_demo.pcap"))
    udp_demo(os.path.join(out, "udp_demo.pcap"))
    print("wrote tcp_server_demo.pcap, tcp_client_demo.pcap, udp_demo.pcap to " + out)


if __name__ == "__main__":
    main()
