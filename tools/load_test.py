#!/usr/bin/env python3
"""Load test: N replays at once (UDP, TCP server and TCP client mixed) against a running PCAP Player.

Usage: python3 tools/load_test.py [--base http://localhost:8090] [--replays 10] [--rate 500]
                                  [--seconds 20] [--pid SERVER_PID]

What it does (stdlib only; psutil is used for --pid if installed, else /proc on Linux):
  1. Writes three captures with one 64-byte message every 1/rate s (UDP, TCP server, TCP client),
     uploads them and saves one record each (a record can play to several targets at once).
  2. Opens a local receiver per replay (UDP socket / TCP listener / TCP connection to the
     replayer's listening port) and starts --replays single replays round-robin over the 3 modes.
  3. Tries one more replay, which must be refused with ACTIVE_LIMIT (409).
  4. While they play: probes GET /health/live (served on the Vert.x event loop, like the WebSocket)
     and GET /api/sessions (worker pool) every 100 ms, and samples the server's CPU if --pid is given.
  5. Reports per replay: messages received, and how far each arrival is from its due time
     (arrival - first arrival - seq / rate): median, p99 and worst, plus HTTP latencies and CPU.

To see what a small pod does, pin the server to 1-2 cores while it runs, e.g.
  Linux:   taskset -c 0 java -jar backend/target/pcap-player.jar      (or docker run --cpus=1)
  Windows: start "" /affinity 1 java -jar backend\\target\\pcap-player.jar   (affinity 3 = 2 cores)
"""
import argparse
import json
import os
import selectors
import socket
import statistics
import struct
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_demo import SERVICE, Capture, TcpConnection  # noqa: E402
from gen_samples import eth, ipv4, udp  # noqa: E402

FRAME = 64
MAGIC = b"LOAD"
MODES = ("UDP_SERVER", "TCP_SERVER", "TCP_CLIENT")
PORTS = {"UDP_SERVER": 5000, "TCP_SERVER": 6000, "TCP_CLIENT": 7000}


def message(seq):
    return (MAGIC + struct.pack("!I", seq)).ljust(FRAME, b".")


def write_captures(folder, rate, seconds):
    count = int(rate * seconds)
    step = 1.0 / rate
    paths = {}

    capture = Capture()
    for seq in range(count):
        capture.add(1.0 + seq * step, eth(ipv4("10.0.0.30", SERVICE, 17, udp(40000, 5000, message(seq)), ident=seq % 65536)))
    paths["UDP_SERVER"] = os.path.join(folder, "load_udp.pcap")
    capture.write(paths["UDP_SERVER"])

    capture = Capture()  # a client talks to our service: the replayer connects and sends the client's side
    connection = TcpConnection(capture, ("10.0.0.5", 51000), (SERVICE, 6000))
    connection.open(0.5)
    for seq in range(count):
        connection._send(1.0 + seq * step, "a", 0x18, message(seq))  # ACK|PSH, no separate ACKs: keep it small
    paths["TCP_SERVER"] = os.path.join(folder, "load_tcp_server.pcap")
    capture.write(paths["TCP_SERVER"])

    capture = Capture()  # our service connected to a source: the replayer listens and sends the source's side
    connection = TcpConnection(capture, (SERVICE, 52000), ("10.0.0.20", 7000))
    connection.open(0.5)
    for seq in range(count):
        connection._send(1.0 + seq * step, "b", 0x18, message(seq))
    paths["TCP_CLIENT"] = os.path.join(folder, "load_tcp_client.pcap")
    capture.write(paths["TCP_CLIENT"])
    return paths, count


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, body=None, raw=None, timeout=30):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "content-type": "application/octet-stream" if raw is not None else "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                text = response.read().decode()
                return response.status, json.loads(text) if text[:1] in ("{", "[") else text
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            return e.code, json.loads(text) if text[:1] == "{" else text


def make_records(api, paths, tag):
    records = {}

    for mode in MODES:
        status, category = api.call("POST", "/api/categories", {"name": f"load-{mode.lower()}-{tag}", "mode": mode})
        assert status == 201, (status, category)
        name = os.path.basename(paths[mode])
        with open(paths[mode], "rb") as capture:
            status, upload = api.call("POST", f"/api/uploads?fileName={name}&uploadedBy=load-test", raw=capture.read(), timeout=300)
        assert status in (200, 201), (status, upload)
        summary = upload["summary"]
        status, record = api.call("POST", "/api/records", {
            "uploadId": upload["uploadId"], "fileId": None, "name": f"load {mode} {tag}", "description": "",
            "categoryId": category["id"], "tagIds": [], "ports": [PORTS[mode]], "ip": None,
            "startUs": summary["startUs"], "endUs": summary["endUs"], "createdBy": "load-test", "resolution": "NEW_NAME"}, timeout=300)
        assert status == 201, (status, record)
        records[mode] = record
    return records


class Receivers:
    """One selector thread timestamps every message from every replay (one clock for all)."""

    def __init__(self):
        self.selector = selectors.DefaultSelector()
        self.arrivals = {}  # replay index -> {seq: perf_counter_ns}
        self.buffers = {}
        self.running = True
        self.lock = threading.Lock()

    def udp(self, index):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
        sock.bind(("127.0.0.1", 0))
        sock.setblocking(False)
        self._register(sock, ("udp", index))
        return sock.getsockname()[1]

    def tcp_listener(self, index):
        server = socket.create_server(("127.0.0.1", 0))
        server.setblocking(False)
        self._register(server, ("accept", index))
        return server.getsockname()[1]

    def tcp_connect(self, index, port):
        for _ in range(200):
            try:
                connection = socket.create_connection(("127.0.0.1", port), timeout=2)
                connection.setblocking(False)
                self._register(connection, ("tcp", index))
                return
            except OSError:
                time.sleep(0.05)
        raise RuntimeError(f"could not connect to the replayer on port {port}")

    def _register(self, sock, tag):
        with self.lock:
            self.arrivals.setdefault(tag[1], {})
            self.selector.register(sock, selectors.EVENT_READ, tag)

    def run(self):
        while self.running:
            with self.lock:
                events = self.selector.select(timeout=0.05) if self.selector.get_map() else []
            for key, _ in events:
                now = time.perf_counter_ns()
                kind, index = key.data
                if kind == "accept":
                    connection, _ = key.fileobj.accept()
                    connection.setblocking(False)
                    self.selector.register(connection, selectors.EVENT_READ, ("tcp", index))
                    continue
                try:
                    data = key.fileobj.recv(1 << 16)
                except (BlockingIOError, ConnectionResetError):
                    continue
                if not data:
                    self.selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                if kind == "udp":
                    self._frame(index, data, now)
                else:
                    buffer = self.buffers.get(key.fileobj, b"") + data
                    while len(buffer) >= FRAME:
                        self._frame(index, buffer[:FRAME], now)
                        buffer = buffer[FRAME:]
                    self.buffers[key.fileobj] = buffer

    def _frame(self, index, frame, now):
        if frame[:4] == MAGIC:
            self.arrivals[index].setdefault(struct.unpack("!I", frame[4:8])[0], now)

    def close(self):
        self.running = False
        for key in list(self.selector.get_map().values()):
            key.fileobj.close()


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(fraction * len(ordered)))] if ordered else float("nan")


class Prober:
    """Latency of an endpoint every 100 ms, from its own thread."""

    def __init__(self, api, path):
        self.api, self.path, self.times, self.errors, self.running = api, path, [], 0, True

    def run(self):
        while self.running:
            started = time.perf_counter()
            try:
                status, _ = self.api.call("GET", self.path, timeout=10)
                if status == 200:
                    self.times.append((time.perf_counter() - started) * 1000)
                else:
                    self.errors += 1
            except Exception:
                self.errors += 1
            time.sleep(0.1)


class CpuSampler:
    """The server's CPU use (100% = one full core) once a second, via psutil or /proc."""

    def __init__(self, pid):
        self.pid, self.samples, self.running = pid, [], True
        self.cores = os.cpu_count() or 1

    def _cpu_seconds(self):
        try:
            import psutil  # optional
            times = psutil.Process(self.pid).cpu_times()
            return times.user + times.system
        except ImportError:
            pass
        if os.name == "nt":
            return self._windows_cpu_seconds()
        with open(f"/proc/{self.pid}/stat") as stat:
            fields = stat.read().rsplit(")", 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")

    def _windows_cpu_seconds(self):
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(0x1000, False, self.pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            raise OSError(f"cannot open process {self.pid}")
        try:
            created, exited, kernel, user = (wintypes.FILETIME() for _ in range(4))
            if not kernel32.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited),
                                            ctypes.byref(kernel), ctypes.byref(user)):
                raise OSError("GetProcessTimes failed")
            ticks = sum((t.dwHighDateTime << 32) + t.dwLowDateTime for t in (kernel, user))
            return ticks / 1e7  # 100 ns units
        finally:
            kernel32.CloseHandle(handle)

    def run(self):
        try:
            last, last_time = self._cpu_seconds(), time.perf_counter()
        except Exception as e:
            print(f"(CPU not measured: {e}; install psutil or run on Linux)")
            return
        while self.running:
            time.sleep(1)
            now_cpu, now = self._cpu_seconds(), time.perf_counter()
            self.samples.append(100 * (now_cpu - last) / (now - last_time))
            last, last_time = now_cpu, now


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8090")
    parser.add_argument("--replays", type=int, default=10)
    parser.add_argument("--rate", type=float, default=500, help="messages per second per replay")
    parser.add_argument("--seconds", type=float, default=20, help="length of each replay")
    parser.add_argument("--pid", type=int, help="server process id, to sample its CPU")
    args = parser.parse_args()
    api = Api(args.base)
    tag = time.strftime("%H%M%S")

    folder = tempfile.mkdtemp(prefix="pcap-load-")
    paths, count = write_captures(folder, args.rate, args.seconds)
    print(f"captures: {count} messages each, {args.rate:g}/s for {args.seconds:g} s ({folder})")
    records = make_records(api, paths, tag)

    receivers = Receivers()
    threading.Thread(target=receivers.run, daemon=True).start()
    probes = [Prober(api, "/health/live"), Prober(api, "/api/sessions")]
    cpu = CpuSampler(args.pid) if args.pid else None
    for worker in probes + ([cpu] if cpu else []):
        threading.Thread(target=worker.run, daemon=True).start()
    time.sleep(1)  # a baseline before the load

    started, modes = [], []
    for index in range(args.replays):
        mode = MODES[index % 3]
        if mode == "UDP_SERVER":
            item = {"recordId": records[mode]["id"], "host": "127.0.0.1", "port": receivers.udp(index), "sleepAfterMs": 0}
        elif mode == "TCP_SERVER":
            item = {"recordId": records[mode]["id"], "host": "127.0.0.1", "port": receivers.tcp_listener(index), "sleepAfterMs": 0}
        else:
            with socket.socket() as probe:  # a free port for the replayer to listen on
                probe.bind(("127.0.0.1", 0))
                port = probe.getsockname()[1]
            item = {"recordId": records[mode]["id"], "host": None, "port": port, "sleepAfterMs": 0}
        status, body = api.call("POST", "/api/sessions", {"items": [item], "playlist": False, "startedBy": "load-test"})
        if status != 201:
            print(f"replay {index} ({mode}) refused: {status} {body}")
            continue
        if mode == "TCP_CLIENT":
            receivers.tcp_connect(index, item["port"])
        started.append(index)
        modes.append(mode)

    status, body = api.call("POST", "/api/sessions", {"items": [{"recordId": records["UDP_SERVER"]["id"], "host": "127.0.0.1",
                                                                  "port": 9, "sleepAfterMs": 0}], "playlist": False, "startedBy": "load-test"})
    over = body.get("code") if isinstance(body, dict) else body
    print(f"one more replay while {len(started)} run: HTTP {status} {over}")

    deadline = time.time() + args.seconds + 15
    while time.time() < deadline:
        status, sessions = api.call("GET", "/api/sessions")
        if status == 200 and not any(s.get("startedBy") == "load-test" for s in sessions):
            break
        time.sleep(0.5)
    time.sleep(0.5)
    for worker in probes + ([cpu] if cpu else []):
        worker.running = False
    receivers.close()

    print(f"\n{'replay':>6} {'mode':<11} {'received':>9} {'median ms':>9} {'p99 ms':>8} {'worst ms':>9}")
    all_deviations = []
    report = {"replays": []}
    for index, mode in zip(started, modes):
        arrivals = receivers.arrivals.get(index, {})
        deviations = []
        if 0 in arrivals:
            first = arrivals[0]
            deviations = [abs((t - first) / 1e6 - seq * 1000 / args.rate) for seq, t in arrivals.items()]
        all_deviations += deviations
        row = {"index": index, "mode": mode, "received": len(arrivals), "expected": count,
               "medianMs": statistics.median(deviations) if deviations else None,
               "p99Ms": percentile(deviations, 0.99), "worstMs": max(deviations) if deviations else None}
        report["replays"].append(row)
        print(f"{index:>6} {mode:<11} {len(arrivals):>5}/{count:<4}"
              f" {row['medianMs'] or float('nan'):>8.3f} {row['p99Ms']:>8.3f} {row['worstMs'] or float('nan'):>9.3f}")
    if all_deviations:
        print(f"{'all':>6} {'':<11} {'':>9} {statistics.median(all_deviations):>9.3f}"
              f" {percentile(all_deviations, 0.99):>8.3f} {max(all_deviations):>9.3f}")
    for probe in probes:
        if probe.times:
            print(f"GET {probe.path:<14} median {statistics.median(probe.times):6.1f} ms, p99 {percentile(probe.times, 0.99):6.1f} ms,"
                  f" worst {max(probe.times):6.1f} ms, errors {probe.errors}")
        report[probe.path] = {"medianMs": statistics.median(probe.times) if probe.times else None,
                              "p99Ms": percentile(probe.times, 0.99), "errors": probe.errors}
    if cpu and cpu.samples:
        busy = cpu.samples[1:-1] or cpu.samples
        print(f"server CPU: average {statistics.mean(busy):.0f}%, peak {max(busy):.0f}% (100% = one core; this host has {cpu.cores})")
        report["cpu"] = {"averagePct": statistics.mean(busy), "peakPct": max(busy)}
    print(json.dumps(report, default=lambda value: None if value != value else value))


if __name__ == "__main__":
    main()
