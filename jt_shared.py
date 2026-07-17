#!/usr/bin/env python3
import importlib.util
import json
import math
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlencode, urlsplit

import requests

try:
    import certifi
except ImportError:  # pragma: no cover - optional dependency
    certifi = None


WECOM_PATH = Path(os.getenv("WECOM_PATH", "/Volumes/samsung_disk_2T/openclaw_workspace/docker-cron/shared/wecom-notify"))
WECOM_APP = os.getenv("WECOM_APP", "bull-monitor")
WECOM_CONFIG = os.getenv("WECOM_CONFIG", "").strip()
WECOM_PROXY = os.getenv("WECOM_PROXY")
ELECTRICWAVE_ENDPOINT = os.getenv(
    "ELECTRICWAVE_ENDPOINT",
    "https://notice.makia98.com/api/v1/notifications",
).strip()
ELECTRICWAVE_TOKEN = (
    os.getenv("ELECTRICWAVE_WEBHOOK_TOKEN", "").strip()
    or os.getenv("WEBHOOK_TOKEN", "").strip()
)
ELECTRICWAVE_RECEIVER_ID = os.getenv("ELECTRICWAVE_RECEIVER_ID", "phone-main").strip()
ELECTRICWAVE_TIMEOUT_SECONDS = float(os.getenv("ELECTRICWAVE_TIMEOUT_SECONDS", "10"))

DATA_DIR = Path(os.getenv("DATA_DIR", "/data"))
MONITOR_FILE = Path(os.getenv("MONITOR_FILE", DATA_DIR / "monitor.json"))
WORKER_STATUS_FILE = Path(os.getenv("WORKER_STATUS_FILE", DATA_DIR / "worker-status.json"))

INTERVAL_SECONDS = {
    "1m": 60,
    "5m": 300,
    "15m": 900,
    "1h": 3600,
    "4h": 14400,
    "1d": 86400,
    "1w": 604800,
}

INTERVAL_MAPPINGS = {
    "binance": {
        "1m": "1m",
        "5m": "5m",
        "15m": "15m",
        "1h": "1h",
        "4h": "4h",
        "1d": "1d",
        "1w": "1w",
    },
    "bybit": {
        "1m": "1",
        "5m": "5",
        "15m": "15",
        "1h": "60",
        "4h": "240",
        "1d": "D",
        "1w": "W",
    },
    "bitget_spot": {
        "1m": "1min",
        "5m": "5min",
        "15m": "15min",
        "1h": "1h",
        "4h": "4h",
        "1d": "1day",
        "1w": "1week",
    },
    "bitget_futures": {
        "1m": "1m",
        "5m": "5m",
        "15m": "15m",
        "1h": "1H",
        "4h": "4H",
        "1d": "1D",
        "1w": "1W",
    },
}

SOURCE_CONFIGS = {
    "spot": {
        "label": "Binance Spot",
        "vendor": "binance",
        "endpoint": "https://data-api.binance.vision/api/v3/klines",
    },
    "futures": {
        "label": "Binance USDT-M",
        "vendor": "binance",
        "endpoint": "https://fapi.binance.com/fapi/v1/klines",
    },
    "bybitSpot": {
        "label": "Bybit Spot",
        "vendor": "bybit",
        "endpoint": "https://api.bybit.com/v5/market/kline",
        "extra_params": {"category": "spot"},
    },
    "bybitLinear": {
        "label": "Bybit USDT Perp",
        "vendor": "bybit",
        "endpoint": "https://api.bybit.com/v5/market/kline",
        "extra_params": {"category": "linear"},
    },
    "bitgetSpot": {
        "label": "Bitget Spot",
        "vendor": "bitget_spot",
        "endpoint": "https://api.bitget.com/api/v2/spot/market/candles",
    },
    "bitgetFutures": {
        "label": "Bitget USDT Perp",
        "vendor": "bitget_futures",
        "endpoint": "https://api.bitget.com/api/v2/mix/market/candles",
        "extra_params": {"productType": "USDT-FUTURES"},
    },
}


def send_wecom(message):
    module_path = WECOM_PATH / "wecom.py"
    if not module_path.exists():
        raise FileNotFoundError(f"wecom module not found: {module_path}")

    spec = importlib.util.spec_from_file_location("external_wecom", module_path)
    if spec is None or spec.loader is None:
        raise ImportError(f"unable to load wecom module from {module_path}")

    module = importlib.util.module_from_spec(spec)
    sys.modules.pop("external_wecom", None)
    spec.loader.exec_module(module)

    if WECOM_CONFIG:
        module.CONFIG_FILE = Path(WECOM_CONFIG)
    if WECOM_PROXY is not None:
        proxy = WECOM_PROXY.strip()
        module.PROXY = proxy
        module.PROXIES = {"http": proxy, "https": proxy} if proxy else None

    send = getattr(module, "send")
    return bool(send(message, app=WECOM_APP))


def configured_notification_channels():
    channels = ["wecom"]
    if ELECTRICWAVE_TOKEN:
        channels.append("electricwave")
    return channels


def post_json_with_openssl(url, headers, payload, timeout):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise RuntimeError("OpenSSL fallback only supports https endpoints")

    port = parsed.port or 443
    path = parsed.path or "/"
    if parsed.query:
        path = f"{path}?{parsed.query}"

    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request_headers = {
        "Host": parsed.hostname,
        "Content-Type": "application/json",
        "Content-Length": str(len(body)),
        "Connection": "close",
        **headers,
    }
    header_block = "".join(f"{key}: {value}\r\n" for key, value in request_headers.items())
    request = f"POST {path} HTTP/1.1\r\n{header_block}\r\n".encode("utf-8") + body
    result = subprocess.run(
        [
            "openssl",
            "s_client",
            "-quiet",
            "-connect",
            f"{parsed.hostname}:{port}",
            "-servername",
            parsed.hostname,
        ],
        input=request,
        capture_output=True,
        timeout=timeout + 5,
        check=False,
    )
    output = result.stdout.decode("utf-8", errors="replace")
    status_line = output.splitlines()[0] if output else ""
    parts = status_line.split()
    if len(parts) >= 2 and parts[1].isdigit():
        status_code = int(parts[1])
        if status_code in {200, 201, 202}:
            return True
        raise RuntimeError(f"ElectricWave http {status_code}: {output[:300]}")
    error = result.stderr.decode("utf-8", errors="replace")[:300]
    raise RuntimeError(f"ElectricWave OpenSSL fallback failed: {error or status_line}")


def send_electricwave(message, title=None, priority="high", idempotency_key=None, group_key=None, data=None):
    if not ELECTRICWAVE_TOKEN:
        raise RuntimeError("ElectricWave webhook token is not configured")
    if not ELECTRICWAVE_ENDPOINT:
        raise RuntimeError("ElectricWave endpoint is not configured")
    if not ELECTRICWAVE_RECEIVER_ID:
        raise RuntimeError("ElectricWave receiver id is not configured")

    notification_title = (title or first_non_empty_line(message) or "JT 抄底信号")[:128]
    body = str(message)[:1024]
    payload = {
        "receiver_id": ELECTRICWAVE_RECEIVER_ID,
        "title": notification_title,
        "body": body,
        "priority": priority,
    }
    if idempotency_key:
        payload["idempotency_key"] = str(idempotency_key)[:128]
    if group_key:
        payload["group_key"] = str(group_key)[:64]
    if data:
        payload["data"] = data

    headers = {
        "Authorization": f"Bearer {ELECTRICWAVE_TOKEN}",
        "Content-Type": "application/json",
    }

    try:
        response = requests.post(
            ELECTRICWAVE_ENDPOINT,
            headers=headers,
            json=payload,
            timeout=ELECTRICWAVE_TIMEOUT_SECONDS,
        )
    except requests.RequestException as exc:
        try:
            return post_json_with_openssl(
                ELECTRICWAVE_ENDPOINT,
                headers,
                payload,
                ELECTRICWAVE_TIMEOUT_SECONDS,
            )
        except Exception as fallback_exc:
            raise RuntimeError(
                f"ElectricWave unavailable: {exc}; OpenSSL fallback failed: {fallback_exc}"
            ) from fallback_exc

    if response.status_code in {200, 201, 202}:
        return True
    raise RuntimeError(f"ElectricWave http {response.status_code}: {response.text[:300]}")


def send_notification(message, title=None, priority="high", idempotency_key=None, group_key=None, data=None, channels=None):
    selected_channels = configured_notification_channels() if channels is None else channels
    results = {}
    for channel in selected_channels:
        try:
            if channel == "wecom":
                ok = send_wecom(message)
            elif channel == "electricwave":
                ok = send_electricwave(
                    message,
                    title=title,
                    priority=priority,
                    idempotency_key=idempotency_key,
                    group_key=group_key,
                    data=data,
                )
            else:
                raise RuntimeError(f"unknown notification channel: {channel}")
            results[channel] = {"ok": bool(ok)}
        except Exception as exc:
            results[channel] = {"ok": False, "error": str(exc)}
    return results


def notification_results_ok(results):
    return bool(results) and all(item.get("ok") for item in results.values())


def first_non_empty_line(text):
    for line in str(text).splitlines():
        line = line.strip()
        if line:
            return line
    return ""


def fetch_remote_klines(source, symbol, interval, limit):
    config = SOURCE_CONFIGS[source]
    vendor = config["vendor"]
    mapped_interval = INTERVAL_MAPPINGS[vendor].get(interval)
    if not mapped_interval:
        raise ValueError(f"unsupported interval for {source}: {interval}")

    params = build_vendor_params(config, symbol, mapped_interval, limit)
    url = f'{config["endpoint"]}?{urlencode(params)}'
    payload = fetch_json(url)
    return normalize_vendor_rows(vendor, payload)


def build_vendor_params(config, symbol, interval, limit):
    vendor = config["vendor"]
    params = dict(config.get("extra_params", {}))
    if vendor in {"binance", "bybit"}:
        params.update({"symbol": symbol, "interval": interval, "limit": str(limit)})
        return params
    if vendor in {"bitget_spot", "bitget_futures"}:
        params.update({"symbol": symbol, "granularity": interval, "limit": str(limit)})
        return params
    raise ValueError(f"unsupported vendor: {vendor}")


def fetch_json(url):
    verify = certifi.where() if certifi is not None else True
    try:
        response = requests.get(
            url,
            headers={"User-Agent": "jt-regime-chart/1.0"},
            timeout=15,
            verify=verify,
        )
    except requests.RequestException as exc:
        raise RuntimeError(f"upstream unavailable: {exc}") from exc

    if not response.ok:
        raise RuntimeError(f"upstream http {response.status_code}: {response.text[:300]}")

    return response.json()


def normalize_vendor_rows(vendor, payload):
    if vendor == "binance":
        if not isinstance(payload, list) or not payload:
            raise RuntimeError("binance returned no kline data")
        return payload

    if vendor == "bybit":
        if payload.get("retCode") != 0:
            raise RuntimeError(payload.get("retMsg") or "bybit returned an error")
        rows = payload.get("result", {}).get("list") or []
        if not rows:
            raise RuntimeError("bybit returned no kline data")
        return list(reversed(rows))

    if vendor in {"bitget_spot", "bitget_futures"}:
        if payload.get("code") != "00000":
            raise RuntimeError(payload.get("msg") or "bitget returned an error")
        rows = payload.get("data") or []
        if not rows:
            raise RuntimeError("bitget returned no kline data")
        return rows

    raise RuntimeError(f"unsupported vendor: {vendor}")


def rows_to_candles(rows):
    return [
        {
            "time": math.floor(int(row[0]) / 1000),
            "open": float(row[1]),
            "high": float(row[2]),
            "low": float(row[3]),
            "close": float(row[4]),
        }
        for row in rows
    ]


def closed_candles(candles, interval, now_seconds, settle_seconds=10):
    interval_seconds = INTERVAL_SECONDS.get(interval)
    if interval_seconds is None:
        raise ValueError(f"unsupported interval: {interval}")
    cutoff = now_seconds - settle_seconds
    return [
        candle for candle in candles
        if candle["time"] + interval_seconds <= cutoff
    ]


def sma(values, length, index):
    if index < length - 1:
        return None
    total = 0.0
    for i in range(index - length + 1, index + 1):
        value = values[i]
        if value is None or not math.isfinite(value):
            return None
        total += value
    return total / length


def stdev(values, length, index):
    mean = sma(values, length, index)
    if mean is None:
        return None
    sum_sq = 0.0
    for i in range(index - length + 1, index + 1):
        value = values[i]
        if value is None or not math.isfinite(value):
            return None
        delta = value - mean
        sum_sq += delta * delta
    return math.sqrt(sum_sq / length)


def zscore(values, length, index):
    mean = sma(values, length, index)
    sd = stdev(values, length, index)
    if mean is None or sd is None or sd == 0:
        return 0
    return (values[index] - mean) / sd


def wma(values, length, index):
    if index < length - 1:
        return None
    weighted = 0.0
    weight_sum = 0
    for offset in range(length):
        value = values[index - offset]
        if value is None or not math.isfinite(value):
            return None
        weight = length - offset
        weighted += value * weight
        weight_sum += weight
    return weighted / weight_sum


def calculate_jt_regime_oscillator(candles, settings):
    closes = [candle["close"] for candle in candles]
    ret1 = [None] * len(candles)
    ret_mom = [None] * len(candles)
    raw_score = [None] * len(candles)
    output = []

    for i in range(len(candles)):
        if i >= settings["zLength"] + settings["momEnd"]:
            ret1[i] = (closes[i] - closes[i - 1]) / closes[i - 1]
            ret_mom[i] = (
                closes[i - (settings["momStart"] - 1)] - closes[i - settings["momEnd"]]
            ) / closes[i - settings["momEnd"]]

        z1 = zscore(ret1, settings["zLength"], i)
        z_mom = zscore(ret_mom, settings["zLength"], i)
        is_extreme_up = z1 > settings["extThresh"]
        is_extreme_down = z1 < -settings["extThresh"]
        short_term_factor = -z1 if is_extreme_up or is_extreme_down else z1

        raw_score[i] = z_mom + short_term_factor
        score = wma(raw_score, settings["smoothLen"], i)
        if score is not None:
            output.append({
                "index": i,
                "time": candles[i]["time"],
                "value": score,
                "close": candles[i]["close"],
                "high": candles[i]["high"],
                "low": candles[i]["low"],
                "z1": z1,
                "zMom": z_mom,
                "isExtremeUp": is_extreme_up,
                "isExtremeDown": is_extreme_down,
            })

    return output


def enrich_marker(marker, signal_type, strength):
    marker = dict(marker)
    marker["type"] = signal_type
    marker["strength"] = strength
    marker["id"] = f"{signal_type}:{marker['time']}"
    return marker


def build_bottom_markers(oscillator, candles):
    markers = []
    recent_extreme_lookback = 12
    breakout_lookback = 5
    marker_cooldown = 8
    last_marker_index = -math.inf

    for i in range(2, len(oscillator)):
        current = oscillator[i]
        prev = oscillator[i - 1]
        prev2 = oscillator[i - 2]
        recent_extreme_down = any(
            item["isExtremeDown"]
            for item in oscillator[max(0, i - recent_extreme_lookback):i + 1]
        )
        if not recent_extreme_down:
            continue

        still_below_zero = current["value"] < 0
        crossed_above_zero = prev["value"] <= 0 and current["value"] > 0
        first_turn_up = prev["value"] <= prev2["value"] and current["value"] > prev["value"]
        candle_index = current["index"]
        start = max(0, candle_index - breakout_lookback)
        prior_high = max((candle["high"] for candle in candles[start:candle_index]), default=-math.inf)
        reclaimed_short_structure = math.isfinite(prior_high) and current["close"] > prior_high

        if crossed_above_zero and reclaimed_short_structure:
            markers.append(enrich_marker({
                "time": current["time"],
                "position": "belowBar",
                "color": "#4e8cff",
                "shape": "arrowUp",
                "text": "确认抄底",
                "score": current["value"],
                "close": current["close"],
            }, "bottomConfirmed", "confirmed"))
            last_marker_index = i
            continue

        if still_below_zero and first_turn_up and i - last_marker_index >= marker_cooldown:
            markers.append(enrich_marker({
                "time": current["time"],
                "position": "belowBar",
                "color": "#ffcc00",
                "shape": "arrowUp",
                "text": "试探抄底",
                "score": current["value"],
                "close": current["close"],
            }, "bottomTentative", "tentative"))
            last_marker_index = i

    return markers


def build_top_markers(oscillator, candles):
    markers = []
    recent_extreme_lookback = 12
    breakdown_lookback = 5
    marker_cooldown = 8
    last_marker_index = -math.inf

    for i in range(2, len(oscillator)):
        current = oscillator[i]
        prev = oscillator[i - 1]
        prev2 = oscillator[i - 2]
        recent_extreme_up = any(
            item["isExtremeUp"]
            for item in oscillator[max(0, i - recent_extreme_lookback):i + 1]
        )
        if not recent_extreme_up:
            continue

        still_above_zero = current["value"] > 0
        crossed_below_zero = prev["value"] >= 0 and current["value"] < 0
        first_turn_down = prev["value"] >= prev2["value"] and current["value"] < prev["value"]
        candle_index = current["index"]
        start = max(0, candle_index - breakdown_lookback)
        prior_low = min((candle["low"] for candle in candles[start:candle_index]), default=math.inf)
        lost_short_structure = math.isfinite(prior_low) and current["close"] < prior_low

        if crossed_below_zero and lost_short_structure:
            markers.append(enrich_marker({
                "time": current["time"],
                "position": "aboveBar",
                "color": "#ff5c5c",
                "shape": "arrowDown",
                "text": "确认逃顶",
                "score": current["value"],
                "close": current["close"],
            }, "topConfirmed", "confirmed"))
            last_marker_index = i
            continue

        if still_above_zero and first_turn_down and i - last_marker_index >= marker_cooldown:
            markers.append(enrich_marker({
                "time": current["time"],
                "position": "aboveBar",
                "color": "#ff9f0a",
                "shape": "arrowDown",
                "text": "试探逃顶",
                "score": current["value"],
                "close": current["close"],
            }, "topTentative", "tentative"))
            last_marker_index = i

    return markers


def build_signal_markers(oscillator, candles):
    return sorted(
        build_bottom_markers(oscillator, candles) + build_top_markers(oscillator, candles),
        key=lambda marker: (marker["time"], marker["text"]),
    )


def signal_key(marker, settings, symbol):
    return ":".join([
        settings["marketType"],
        symbol,
        settings["interval"],
        str(marker["time"]),
        marker["text"],
    ])


def read_json_file(path, default):
    try:
        with Path(path).open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        return default
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"invalid json file {path}: {exc}") from exc


def atomic_write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=str(path.parent),
        text=True,
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_name, path)
        dir_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
    except Exception:
        try:
            os.unlink(tmp_name)
        except FileNotFoundError:
            pass
        raise
