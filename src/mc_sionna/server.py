"""Local HTTP service the Minecraft mod calls. One request at a time (the GPU is shared)."""

import argparse
import json
import traceback
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from .sim import simulate


class Handler(BaseHTTPRequestHandler):
    exports = Path("exports")

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True})
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path != "/simulate":
            return self._send(404, {"ok": False, "error": "not found"})
        try:
            req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            out = self.exports / datetime.now().strftime("%Y-%m-%d_%H-%M-%S_%f")
            result = simulate(req, out)
            self.log_message("simulated %s in %.2fs -> %s", req["size"], result["elapsed_s"], out)
            self._send(200, {"ok": True} | result)
        except Exception as e:
            traceback.print_exc()
            self._send(500, {"ok": False, "error": f"{type(e).__name__}: {e}"})

    def _send(self, code: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    ap = argparse.ArgumentParser(description="Sionna RT service for the mc-sionna Minecraft mod")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--exports", type=Path, default=Path("exports"), help="where each run's scene + channels go")
    args = ap.parse_args()
    Handler.exports = args.exports
    srv = HTTPServer((args.host, args.port), Handler)
    print(f"mc-sionna listening on http://{args.host}:{args.port}, exports -> {args.exports.resolve()}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
