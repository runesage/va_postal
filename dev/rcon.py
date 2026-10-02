#!/usr/bin/env python3
"""Minimal Minecraft RCON client, used by dev/test-server.sh to send setup commands to a running
server while its console stays interactive.

Usage: rcon.py <host> <port> <password> <command>...
Each command's response is printed. Exits non-zero if the server can't be reached or rejects the
password.
"""
import socket
import struct
import sys

LOGIN, COMMAND = 3, 2


def send(sock, req_id, kind, body):
    payload = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def recv(sock):
    def read(n):
        data = b""
        while len(data) < n:
            chunk = sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            data += chunk
        return data

    (length,) = struct.unpack("<i", read(4))
    req_id, _kind = struct.unpack("<ii", read(8))
    body = read(length - 8)[:-2].decode("utf-8", errors="replace")
    return req_id, body


def main():
    if len(sys.argv) < 5:
        print(__doc__, file=sys.stderr)
        return 2
    host, port, password, commands = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4:]
    with socket.create_connection((host, port), timeout=10) as sock:
        send(sock, 1, LOGIN, password)
        req_id, _ = recv(sock)
        if req_id == -1:
            print("RCON login rejected", file=sys.stderr)
            return 1
        for i, command in enumerate(commands, start=2):
            send(sock, i, COMMAND, command)
            _, body = recv(sock)
            print(f"> {command}" + (f"\n{body}" if body.strip() else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
