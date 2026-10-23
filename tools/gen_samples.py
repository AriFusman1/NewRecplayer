#!/usr/bin/env python3
"""Generates sample captures for checking the PCAP Player parser (stdlib only).

Usage: python3 tools/gen_samples.py OUT_DIR

Writes, per scenario, a capture plus <name>.expected.json describing the messages the
replayer must produce (offset from range start in µs, length, sha256) and the warnings.
"""
import hashlib
import json
import os
import struct
import sys

T0 = 1_700_000_000  # 14/11/2023 22:13:20 UTC


def ip_bytes(s):
    return bytes(int(x) for x in s.split("."))


def ipv4(src, dst, proto, payload, ident=1, mf=False, frag_off=0, total_len=None):
    tl = 20 + len(payload) if total_len is None else total_len
    flags = (0x2000 if mf else 0) | (frag_off // 8)
    return struct.pack("!BBHHHBBH4s4s", 0x45, 0, tl, ident, flags, 64, proto, 0,
                       ip_bytes(src), ip_bytes(dst)) + payload


def udp(sport, dport, data):
    return struct.pack("!HHHH", sport, dport, 8 + len(data), 0) + data


def tcp(sport, dport, seq, ack, flags, data=b""):
    return struct.pack("!HHIIBBHHH", sport, dport, seq & 0xFFFFFFFF, ack & 0xFFFFFFFF,
                       5 << 4, flags, 65535, 0, 0) + data


FIN, SYN, RST, PSH, ACK = 0x01, 0x02, 0x04, 0x08, 0x10


def eth(ip, vlans=()):
    hdr = b"\x00\x11\x22\x33\x44\x55" + b"\x66\x77\x88\x99\xaa\xbb"
    for i, v in enumerate(vlans):
        hdr += struct.pack("!HH", 0x88A8 if (len(vlans) == 2 and i == 0) else 0x8100, v)
    return hdr + struct.pack("!H", 0x0800) + ip


def eth_other(ethertype, body):
    return b"\x00" * 12 + struct.pack("!H", ethertype) + body


def sll(ip):
    return struct.pack("!HHH8sH", 0, 1, 6, b"\x00" * 8, 0x0800) + ip


def sll2(ip):
    return struct.pack("!HHIHBB8s", 0x0800, 0, 2, 1, 0, 6, b"\x00" * 8) + ip


# ------------------------------------------------------------------ writers

def write_pcap(path, frames, linktype, endian="<", nanos=False, snaplen=262144):
    """frames: list of (ts_float_or_(sec,frac), data, orig_len_or_None)"""
    magic = 0xA1B23C4D if nanos else 0xA1B2C3D4
    with open(path, "wb") as f:
        f.write(struct.pack(endian + "IHHiIII", magic, 2, 4, 0, 0, snaplen, linktype))
        for ts, data, orig in frames:
            sec, frac = ts
            cap = data[:snaplen]
            f.write(struct.pack(endian + "IIII", sec, frac, len(cap), orig or len(data)))
            f.write(cap)


def pad4(b):
    return b + b"\x00" * ((4 - len(b) % 4) % 4)


def ng_block(endian, btype, body):
    body = pad4(body)
    total = 12 + len(body)
    return struct.pack(endian + "II", btype, total) + body + struct.pack(endian + "I", total)


def write_pcapng(path, interfaces, packets, endian=">"):
    """interfaces: list of (linktype, tsresol_byte or None); packets: (iface, ts_units, data, orig)"""
    out = b""
    shb_body = struct.pack(endian + "IHHq", 0x1A2B3C4D, 1, 0, -1)
    out += ng_block(endian, 0x0A0D0D0A, shb_body)
    for lt, res in interfaces:
        opts = b""
        if res is not None:
            opts += struct.pack(endian + "HH", 9, 1) + bytes([res]) + b"\x00\x00\x00"
        opts += struct.pack(endian + "HH", 0, 0)
        out += ng_block(endian, 1, struct.pack(endian + "HHI", lt, 0, 0) + opts)
    # an unknown block type that must be skipped
    out += ng_block(endian, 0x00000BAD, b"junk")
    for iface, ts, data, orig in packets:
        body = struct.pack(endian + "IIIII", iface, ts >> 32, ts & 0xFFFFFFFF, len(data), orig or len(data)) + data
        out += ng_block(endian, 6, body)
    with open(path, "wb") as f:
        f.write(out)


def msg(offset_us, data):
    return {"offsetUs": offset_us, "len": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def pattern(n, seed):
    return bytes((i * 7 + seed) & 0xFF for i in range(n))


# ------------------------------------------------------------------ scenario 1: UDP

def udp_scenario(out):
    src, dst = "10.1.1.1", "10.1.1.2"
    frames, expected = [], []

    def at(ms):
        us = int(round(ms * 1000))
        return (T0 + us // 1_000_000, us % 1_000_000)

    d1 = pattern(100, 1)
    frames.append((at(0), eth(ipv4(src, dst, 17, udp(40000, 5000, d1), ident=1)), None))
    expected.append(msg(0, d1))

    # ARP and IPv6 are ignored silently
    frames.append((at(1), eth_other(0x0806, b"\x00" * 28), None))
    frames.append((at(2), eth_other(0x86DD, b"\x60" + b"\x00" * 39), None))

    # 3000-byte datagram in three fragments, arriving out of order; completes at 12 ms
    d2 = pattern(3000, 2)
    whole = udp(40000, 5000, d2)
    f1, f2, f3 = whole[:1480], whole[1480:2960], whole[2960:]
    frames.append((at(10), eth(ipv4(src, dst, 17, f1, ident=2, mf=True, frag_off=0, total_len=20 + len(f1))), None))
    frames.append((at(11), eth(ipv4(src, dst, 17, f3, ident=2, mf=False, frag_off=2960, total_len=20 + len(f3))), None))
    frames.append((at(12), eth(ipv4(src, dst, 17, f2, ident=2, mf=True, frag_off=1480, total_len=20 + len(f2)), vlans=(10,)), None))
    expected.append(msg(12000, d2))

    # same datagram captured twice (second copy on a VLAN interface, 0.5 ms later)
    d3 = pattern(50, 3)
    frames.append((at(20), eth(ipv4(src, dst, 17, udp(40000, 5000, d3), ident=3)), None))
    frames.append((at(20.5), eth(ipv4(src, dst, 17, udp(40000, 5000, d3), ident=3), vlans=(10, 20)), None))
    expected.append(msg(20000, d3))

    # incomplete fragment set (only the first fragment)
    d4 = udp(40000, 5000, pattern(2000, 4))
    frames.append((at(30), eth(ipv4(src, dst, 17, d4[:1480], ident=4, mf=True, total_len=1500)), None))

    # snaplen-truncated datagram: captured 200 of 1042 bytes
    d5 = eth(ipv4(src, dst, 17, udp(40000, 5000, pattern(1000, 5)), ident=5))
    frames.append((at(40), d5[:200], len(d5)))

    # other port: filtered out
    frames.append((at(50), eth(ipv4(src, dst, 17, udp(40000, 5001, b"other"), ident=6)), None))

    # Ethernet padding must be trimmed via IP total length
    d7 = pattern(20, 7)
    frames.append((at(1500), eth(ipv4(src, dst, 17, udp(40000, 5000, d7), ident=7)) + b"\x00" * 6, None))
    expected.append(msg(1500000, d7))

    write_pcap(os.path.join(out, "udp.pcap"), frames, 1, endian="<")
    # same traffic as little-endian pcapng with the default (microsecond) resolution
    ng = [(0, (sec * 1_000_000 + us), data[:262144], orig) for (sec, us), data, orig in frames]
    write_pcapng(os.path.join(out, "udp_le.pcapng"), [(1, None)], ng, endian="<")
    return {"file": "udp.pcap", "mode": "UDP_SERVER", "ports": [5000], "startUs": T0 * 1_000_000,
            "endUs": T0 * 1_000_000 + 1_500_000, "messages": expected, "reconnects": [],
            "warnings": {"TRUNCATED_PACKETS": 1, "UDP_DUPLICATES": 1, "INCOMPLETE_FRAGMENTS": 1}}


# ------------------------------------------------------------------ scenario 2: TCP server (pcapng)

def tcp_server_scenario(out):
    c, s = "10.0.0.1", "10.0.0.2"
    cisn, sisn = 1000, 5000
    data = pattern(70, 9) + pattern(30000, 11)
    pk, expected = [], []
    base_ns = T0 * 1_000_000_000

    def ts(ms):  # iface resolution 1 ns
        return base_ns + int(round(ms * 1_000_000))

    def seg(lo, hi, ms, iface=0, flags=ACK | PSH, sport=50000, gro=False):
        payload = tcp(sport, 6000, cisn + 1 + lo, sisn + 1, flags, data[lo:hi])
        ip = ipv4(c, s, 6, payload, total_len=0 if gro else None)
        frame = eth(ip) if iface == 0 else sll2(ip)
        pk.append((iface, ts(ms), frame, None))

    def rev(ms, flags, ack, body=b"", seq=None):
        payload = tcp(6000, 50000, sisn + 1 if seq is None else seq, ack, flags, body)
        pk.append((0, ts(ms), eth(ipv4(s, c, 6, payload)), None))

    pk.append((0, ts(0), eth(ipv4(c, s, 6, tcp(50000, 6000, cisn, 0, SYN))), None))
    rev(1, SYN | ACK, cisn + 1, seq=sisn)
    pk.append((0, ts(2), eth(ipv4(c, s, 6, tcp(50000, 6000, cisn + 1, sisn + 1, ACK))), None))
    seg(0, 10, 10)
    expected.append(msg(10000, data[0:10]))
    seg(20, 30, 20)            # out of order
    seg(10, 20, 25)            # fills the hole -> [10,30) readable at 25 ms
    expected.append(msg(25000, data[10:30]))
    rev(26, PSH | ACK, cisn + 1 + 30, body=b"server reply ignored")
    seg(0, 10, 30)             # retransmission
    seg(25, 40, 40)            # overlap -> only [30,40) is new
    expected.append(msg(40000, data[30:40]))
    # [40,50) never captured; service ACKs 60 => capture gap of 10 bytes
    seg(50, 60, 50)
    rev(51, ACK, cisn + 1 + 60)
    expected.append(msg(50000, data[50:60]))
    seg(60, 70, 60, iface=1)   # SLL2 interface
    seg(60, 70, 60.1, iface=0)  # same segment captured on the other interface
    expected.append(msg(60000, data[60:70]))
    seg(70, 30070, 70, gro=True)  # GRO super-packet, IP total length 0
    expected.append(msg(70000, data[70:30070]))
    pk.append((0, ts(80), eth(ipv4(c, s, 6, tcp(50000, 6000, cisn + 1 + 30070, sisn + 1, FIN | ACK))), None))
    rev(81, FIN | ACK, cisn + 1 + 30071)

    # second connection from a new ephemeral port, reset at the end
    d2 = b"again"
    pk.append((0, ts(2000), eth(ipv4(c, s, 6, tcp(50001, 6000, 9000, 0, SYN))), None))
    pk.append((0, ts(2010), eth(ipv4(c, s, 6, tcp(50001, 6000, 9001, 1, ACK | PSH, d2))), None))
    expected.append(msg(2010000, d2))
    pk.append((0, ts(2020), eth(ipv4(c, s, 6, tcp(50001, 6000, 9006, 1, RST))), None))

    write_pcapng(os.path.join(out, "tcp_server.pcapng"), [(1, 9), (276, 9)], pk, endian=">")
    return {"file": "tcp_server.pcapng", "mode": "TCP_SERVER", "ports": [6000], "startUs": T0 * 1_000_000,
            "endUs": T0 * 1_000_000 + 2_020_000, "messages": expected,
            "reconnects": [{"closeUs": 81000, "connectUs": 2000000}],
            "warnings": {"TCP_GAPS": 1}}


# ------------------------------------------------------------------ scenario 3: TCP client (ns pcap, SLL)

def tcp_client_scenario(out):
    svc, src = "10.0.0.2", "10.0.0.9"
    sisn = 0xFFFFFFF0  # source ISN close to wrap-around
    data = pattern(40, 21)
    frames, expected = [], []

    def at(ms):
        ns = int(round(ms * 1_000_000))
        return (T0 + ns // 1_000_000_000, ns % 1_000_000_000)

    frames.append((at(0), sll(ipv4(svc, src, 6, tcp(41000, 7000, 77, 0, SYN))), None))
    frames.append((at(1), sll(ipv4(src, svc, 6, tcp(7000, 41000, sisn, 78, SYN | ACK))), None))
    frames.append((at(100), sll(ipv4(src, svc, 6, tcp(7000, 41000, sisn + 1, 78, ACK | PSH, data[:30]))), None))
    frames.append((at(200), sll(ipv4(src, svc, 6, tcp(7000, 41000, sisn + 31, 78, ACK | PSH, data[30:]))), None))
    expected.append(msg(100000, data[:30]))
    expected.append(msg(200000, data[30:]))

    write_pcap(os.path.join(out, "tcp_client.pcap"), frames, 113, endian=">", nanos=True)
    full = {"file": "tcp_client.pcap", "mode": "TCP_CLIENT", "ports": [7000], "startUs": T0 * 1_000_000,
            "endUs": T0 * 1_000_000 + 200_000, "messages": expected, "reconnects": [], "warnings": {}}
    # same file, range starting mid-connection (after the SYN and the first segment)
    mid = dict(full, name="tcp_client_mid", startUs=T0 * 1_000_000 + 150_000,
               messages=[msg(50000, data[30:])])
    return [full, mid]


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    udp_sc = udp_scenario(out)
    scenarios = [udp_sc, dict(udp_sc, file="udp_le.pcapng", name="udp_le_pcapng"),
                 tcp_server_scenario(out)] + tcp_client_scenario(out)
    for sc in scenarios:
        sc.setdefault("name", sc["file"].split(".")[0])
    with open(os.path.join(out, "expected.json"), "w") as f:
        json.dump(scenarios, f, indent=1)
    print("wrote", len(scenarios), "scenarios to", out)


if __name__ == "__main__":
    main()
