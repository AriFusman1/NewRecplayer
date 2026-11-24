#!/usr/bin/env python3
"""A stand-in for the service, to watch a replay arrive on this machine (stdlib only).

  python3 tools/peer.py tcp-listen 6000           for TCP server records: start this first, then replay
                                                  to 127.0.0.1:6000 (the replayer connects here)
  python3 tools/peer.py tcp-connect 127.0.0.1 7000
                                                  for TCP client records: start the replay first (it listens
                                                  on 7000), then this connects to it
  python3 tools/peer.py udp-listen 5000           for UDP server records: replay to 127.0.0.1:5000

Listens on 127.0.0.1 only. Prints every chunk it receives, with the time since the first one.
Stop with Ctrl+C.
"""
import socket
import sys
import time


def show(started, data, source=""):
    elapsed = time.monotonic() - started[0] if started else 0.0
    if not started:
        started.append(time.monotonic())
    text = data.decode("utf-8", errors="replace").rstrip("\n").replace("\n", "\n" + " " * 12)
    print(f"[{elapsed:8.3f}s] {source}{text}  ({len(data)} bytes)", flush=True)


def read_stream(connection):
    started = []
    with connection:
        while True:
            data = connection.recv(65536)
            if not data:
                print("connection closed by the replayer", flush=True)
                return
            show(started, data)


def tcp_listen(port):
    with socket.create_server(("127.0.0.1", port)) as server:
        print(f"listening on TCP port {port}; replay a TCP server record to 127.0.0.1:{port}", flush=True)
        while True:
            connection, address = server.accept()
            print(f"replayer connected from {address[0]}:{address[1]}", flush=True)
            read_stream(connection)


def tcp_connect(host, port):
    print(f"connecting to {host}:{port} (the replay must already be waiting for a connection)", flush=True)
    connection = socket.create_connection((host, port), timeout=10)
    connection.settimeout(None)  # the 10 s is for connecting; a replay may pause for longer
    read_stream(connection)


def udp_listen(port):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
        server.bind(("127.0.0.1", port))
        print(f"listening on UDP port {port}; replay a UDP record to 127.0.0.1:{port}", flush=True)
        started = []
        while True:
            data, address = server.recvfrom(65535)
            show(started, data, f"{address[0]}:{address[1]}  ")


def main():
    args = sys.argv[1:]
    try:
        if len(args) == 2 and args[0] == "tcp-listen":
            tcp_listen(int(args[1]))
        elif len(args) == 3 and args[0] == "tcp-connect":
            tcp_connect(args[1], int(args[2]))
        elif len(args) == 2 and args[0] == "udp-listen":
            udp_listen(int(args[1]))
        else:
            sys.exit(__doc__)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
