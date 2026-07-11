#!/usr/bin/env python3
import json
import os
import sys
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from jt_shared import (
    MONITOR_FILE,
    SOURCE_CONFIGS,
    WORKER_STATUS_FILE,
    atomic_write_json,
    closed_candles,
    fetch_remote_klines,
    read_json_file,
    rows_to_candles,
    notification_results_ok,
    send_notification,
)

ROOT = Path(__file__).resolve().parent
DEFAULT_HOST = os.getenv("HOST", "127.0.0.1")
DEFAULT_PORT = int(os.getenv("PORT", "8088"))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/api/market/klines":
            self.handle_market_klines()
            return
        if path == "/api/monitor/status":
            self.handle_monitor_status()
            return
        self.route_root_to_chart()
        super().do_GET()

    def do_HEAD(self):
        self.route_root_to_chart()
        super().do_HEAD()

    def route_root_to_chart(self):
        if urlsplit(self.path).path in ("", "/"):
            self.path = "/jt-regime-oscillator.html"

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "content-type")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        path = urlsplit(self.path).path
        if path == "/api/wecom/notify":
            self.handle_wecom_notify()
            return
        if path == "/api/monitor/sync":
            self.handle_monitor_sync()
            return
        self.send_error(404, "not found")

    def handle_wecom_notify(self):
        try:
            payload = self.read_json_body()
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

            results = send_notification(message, title="JT 抄底信号通知测试", group_key="jt-regime-test")
            ok = notification_results_ok(results)
            self.write_json(200 if ok else 502, {"ok": ok, "channels": results})
        except Exception as exc:
            self.write_json(500, {"ok": False, "error": str(exc)})

    def handle_monitor_sync(self):
        try:
            payload = self.read_json_body()
            monitor = build_monitor_config(payload)
            atomic_write_json(MONITOR_FILE, monitor)
            self.write_json(200, {
                "ok": True,
                "path": str(MONITOR_FILE),
                "enabled_symbols": monitor["enabled_symbols"],
                "baseline": monitor["baseline"],
            })
        except ValueError as exc:
            self.write_json(400, {"ok": False, "error": str(exc)})
        except Exception as exc:
            self.write_json(502, {"ok": False, "error": str(exc)})

    def handle_monitor_status(self):
        try:
            status = read_json_file(WORKER_STATUS_FILE, {
                "state": "not_started",
                "message": "Worker 尚未写入状态",
            })
            monitor = read_json_file(MONITOR_FILE, None)
            self.write_json(200, {
                "ok": True,
                "status": status,
                "monitor": summarize_monitor(monitor),
            })
        except Exception as exc:
            self.write_json(500, {"ok": False, "error": str(exc)})

    def read_json_body(self):
        length = int(self.headers.get("content-length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def handle_market_klines(self):
        try:
            query = parse_qs(urlsplit(self.path).query)
            source = first_query_value(query, "source")
            symbol = first_query_value(query, "symbol").upper()
            interval = first_query_value(query, "interval")
            limit = int(first_query_value(query, "limit", "500"))

            validate_market_request(source, symbol, interval, limit)
            rows = fetch_remote_klines(source, symbol, interval, limit)
            self.write_json(200, rows)
        except ValueError as exc:
            self.write_json(400, {"ok": False, "error": str(exc)})
        except Exception as exc:
            self.write_json(502, {"ok": False, "error": str(exc)})

    def write_json(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def validate_market_request(source, symbol, interval, limit):
    if source not in SOURCE_CONFIGS:
        raise ValueError(f"unsupported source: {source}")
    if not symbol:
        raise ValueError("symbol is required")
    if interval not in {"1m", "5m", "15m", "1h", "4h", "1d", "1w"}:
        raise ValueError(f"unsupported interval: {interval}")
    if limit < 1 or limit > 1000:
        raise ValueError("limit must be between 1 and 1000")


def build_monitor_config(payload):
    settings = normalize_settings(payload.get("settings") or {})
    symbols = normalize_symbols(payload.get("symbols") or [])
    enabled_symbols = normalize_symbols(payload.get("enabled_symbols") or [])
    enabled_symbols = [symbol for symbol in enabled_symbols if symbol in symbols]

    if not symbols:
        raise ValueError("symbols is required")
    if not enabled_symbols:
        raise ValueError("enabled_symbols is required")
    if settings["momStart"] >= settings["momEnd"]:
        raise ValueError("momStart must be less than momEnd")

    now = time.time()
    baseline = {}
    for symbol in enabled_symbols:
        rows = fetch_remote_klines(settings["marketType"], symbol, settings["interval"], settings["limit"])
        candles = closed_candles(rows_to_candles(rows), settings["interval"], now)
        if not candles:
            raise ValueError(f"{symbol} has no candle closed for at least 10 seconds")
        baseline[symbol] = candles[-1]["time"]

    previous = read_json_file(MONITOR_FILE, {})
    sent_signal_keys = previous.get("sent_signal_keys", [])
    if not isinstance(sent_signal_keys, list):
        sent_signal_keys = []
    sent_signal_channel_keys = previous.get("sent_signal_channel_keys", [])
    if not isinstance(sent_signal_channel_keys, list):
        sent_signal_channel_keys = []

    return {
        "version": 1,
        "synced_at": int(now),
        "settings": settings,
        "symbols": symbols,
        "enabled_symbols": enabled_symbols,
        "baseline": baseline,
        "sent_signal_keys": sent_signal_keys[-1000:],
        "sent_signal_channel_keys": sent_signal_channel_keys[-2000:],
    }


def normalize_settings(raw):
    settings = {
        "interval": str(raw.get("interval", "1h")),
        "limit": clamp_int(raw.get("limit", 500), 120, 1000),
        "marketType": str(raw.get("marketType", "spot")),
        "momStart": max(2, int(float(raw.get("momStart", 2)))),
        "momEnd": max(3, int(float(raw.get("momEnd", 52)))),
        "zLength": max(10, int(float(raw.get("zLength", 52)))),
        "extThresh": float(raw.get("extThresh", 2)),
        "smoothLen": max(1, int(float(raw.get("smoothLen", 8)))),
    }
    validate_market_request(settings["marketType"], "BTCUSDT", settings["interval"], settings["limit"])
    return settings


def clamp_int(value, minimum, maximum):
    number = int(float(value))
    return max(minimum, min(maximum, number))


def normalize_symbols(values):
    symbols = []
    for value in values:
        symbol = str(value).strip().upper()
        if symbol and symbol.replace("_", "").isalnum() and symbol not in symbols:
            symbols.append(symbol)
    return symbols


def summarize_monitor(monitor):
    if not monitor:
        return {"configured": False}
    return {
        "configured": True,
        "synced_at": monitor.get("synced_at"),
        "settings": monitor.get("settings"),
        "enabled_symbols": monitor.get("enabled_symbols", []),
        "sent_signal_count": len(monitor.get("sent_signal_keys", [])),
        "sent_signal_channel_count": len(monitor.get("sent_signal_channel_keys", [])),
        "baseline": monitor.get("baseline", {}),
    }


def first_query_value(query, key, default=""):
    values = query.get(key)
    if not values:
        return default
    return values[0]


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    host = os.getenv("HOST", DEFAULT_HOST)
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"Serving {ROOT} on http://{host}:{port}/")
    print("WeCom notify endpoint: /api/wecom/notify")
    print("Monitor endpoints: GET /api/monitor/status, POST /api/monitor/sync")
    server.serve_forever()


if __name__ == "__main__":
    main()
