#!/usr/bin/env python3
"""Installs app-debug.apk on the phone through the Bridge tunnel (no cable).

Needs a dumbpipe tunnel to the phone on 127.0.0.1:7555 (the Mac app's, or
`dumbpipe connect-tcp --addr 127.0.0.1:7555 <ticket>`), and the phone to be
"ready" (daemon running). The daemon receives the bytes and runs `pm install`.
"""
import os, socket, sys

apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app/build/outputs/apk/debug/app-debug.apk")
size = os.path.getsize(apk)
s = socket.create_connection(("127.0.0.1", 7555), timeout=120)
s.sendall(f"INSTALL {size}\n".encode())
with open(apk, "rb") as f:
    sent = 0
    while chunk := f.read(65536):
        s.sendall(chunk); sent += len(chunk)
        print(f"\r{sent * 100 // size}%", end="", flush=True)
print()
reply = b""
while not reply.endswith(b"\n"):
    c = s.recv(1024)
    if not c: break
    reply += c
print(reply.decode().strip())
