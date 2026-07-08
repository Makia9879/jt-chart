#!/usr/bin/env python3
import json
import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parent
WECOM_PATH = Path(os.getenv("WECOM_PATH", "/Volumes/samsung_disk_2T/openclaw_workspace/docker-cron/shared/wecom-notify"))
WECOM_APP = os.getenv("WECOM_APP", "bull-monitor")
DEFAULT_HOST = os.getenv("HOST", "127.0.0.1")
DEFAULT_PORT = int(os.getenv("PORT", "8088"))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "content-type")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        if self.path != "/api/wecom/notify":
            self.send_error(404, "not found")
            return

        try:
            length = int(self.headers.get("content-length", "0"))
            payload = json.loads(self.rfile.read(length) or b"{}")
            message = str(payload.get("message", "")).strip()
            dry_run = bool(payload.get("dry_run", False))

            if not message:
                self.write_json(400, {"ok": False, "error": "message is required"})
                return

            if len(message.encode("utf-8")) > 1800:
                self.write_json(400, {"ok": False, "error": "message is too long"})
                return

            if dry_run:
                self.write_json(200, {"ok": True, "dry_run": True})
                return

            ok = send_wecom(message)
            self.write_json(200 if ok else 502, {"ok": ok})
        except Exception as exc:
            self.write_json(500, {"ok": False, "error": str(exc)})

    def write_json(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def send_wecom(message):
    if str(WECOM_PATH) not in sys.path:
        sys.path.insert(0, str(WECOM_PATH))

    from wecom import send

    return bool(send(message, app=WECOM_APP))


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    host = os.getenv("HOST", DEFAULT_HOST)
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"Serving {ROOT} on http://{host}:{port}/")
    print("WeCom notify endpoint: /api/wecom/notify")
    server.serve_forever()


if __name__ == "__main__":
    main()
