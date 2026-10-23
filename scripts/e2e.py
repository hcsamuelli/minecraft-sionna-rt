"""End-to-end check against a running server (mod/run/server.properties enables RCON).

Builds a small walled yard with a window, places a transmitter and receivers through RCON,
and checks that the mod drew rays and the Python service wrote an export.

    uv run python scripts/e2e.py
"""

import socket
import struct
import sys
import time
from pathlib import Path


class Rcon:
    def __init__(self, host="127.0.0.1", port=25575, password="mcsionna-dev"):
        self.s = socket.create_connection((host, port), timeout=10)
        self._req(3, password)

    def _req(self, kind: int, body: str) -> str:
        data = struct.pack("<ii", 1, kind) + body.encode() + b"\0\0"
        self.s.sendall(struct.pack("<i", len(data)) + data)
        n = struct.unpack("<i", self._read(4))[0]
        _, _, payload = struct.unpack("<ii", self._read(8)) + (self._read(n - 8),)
        return payload[:-2].decode()

    def _read(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            buf += self.s.recv(n - len(buf))
        return buf

    def __call__(self, cmd: str) -> str:
        out = self._req(2, cmd)
        print(f"> {cmd}\n  {out}")
        return out


def count(rcon: Rcon, tag: str) -> int:
    out = rcon(f"execute if entity @e[tag={tag}]")
    return int(out.rsplit(" ", 1)[-1]) if "Test passed" in out else 0


def main():
    rcon = Rcon()
    exports = Path("exports")
    before = set(exports.glob("*")) if exports.exists() else set()
    y = -60  # superflat ground surface is at y=-61
    rcon("forceload add -40 -40 40 40")
    rcon("sionna set auto_run false")
    rcon("sionna set radius 32")
    for x in range(-30, 31, 20):  # /fill is capped at 32768 blocks per call
        rcon(f"fill {x} {y} -30 {min(x + 19, 30)} {y + 12} 30 air")
    # a stone wall with a glass window, and a brick house corner
    rcon(f"fill 0 {y} -12 0 {y + 6} 12 stone")
    rcon(f"fill 0 {y + 2} -2 0 {y + 3} 2 glass")
    rcon(f"fill 8 {y} 6 14 {y + 5} 6 bricks")
    rcon(f"setblock -10 {y + 2} 0 mc_sionna:transmitter")
    rcon(f"setblock 10 {y + 2} 0 mc_sionna:receiver")
    rcon(f"setblock -4 {y + 1} 14 mc_sionna:receiver")
    rcon("sionna clear")
    rcon(f"execute positioned 0 {y + 2} 0 run sionna run")
    for _ in range(120):
        time.sleep(1)
        if count(rcon, "mc_sionna_ray"):
            break
    rays, labels = count(rcon, "mc_sionna_ray"), count(rcon, "mc_sionna_label")
    new = sorted(set(exports.glob("*")) - before) if exports.exists() else []
    print(f"rays={rays} labels={labels} new_exports={[p.name for p in new]}")
    ok = rays > 0 and labels == 3 and new and (new[-1] / "scene.xml").exists() and (new[-1] / "channels.npz").exists()
    print("E2E OK" if ok else "E2E FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
