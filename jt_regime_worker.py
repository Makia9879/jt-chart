#!/usr/bin/env python3
import os
import time
import traceback

from jt_shared import (
    MONITOR_FILE,
    SOURCE_CONFIGS,
    WORKER_STATUS_FILE,
    atomic_write_json,
    build_signal_markers,
    calculate_jt_regime_oscillator,
    closed_candles,
    fetch_remote_klines,
    configured_notification_channels,
    read_json_file,
    rows_to_candles,
    send_notification,
    signal_key,
)


SCAN_SECONDS = int(os.getenv("WORKER_SCAN_SECONDS", "15"))


def utc8_time_text(seconds):
    return time.strftime("%Y-%m-%d %H:%M", time.gmtime(seconds + 8 * 3600))


def marker_message(marker, settings, symbol):
    source = SOURCE_CONFIGS.get(settings["marketType"], SOURCE_CONFIGS["spot"])
    score = marker["score"]
    signal_name = marker["text"]
    if signal_name == "确认抄底":
        rule = "极端下跌后，Regime Score 上穿 0 轴且收盘价突破近 5 根结构高点。"
        reminder = "这是确认信号，仍需结合仓位和风险控制。"
        icon = "🔵"
        title = "JT 抄底信号"
    elif signal_name == "试探抄底":
        rule = "极端下跌后，Regime Score 在 0 轴下方首次拐头。"
        reminder = "这是试探信号，不是确认买点。"
        icon = "🟡"
        title = "JT 抄底信号"
    elif signal_name == "确认逃顶":
        rule = "极端上涨后，Regime Score 下穿 0 轴且收盘价跌破近 5 根结构低点。"
        reminder = "这是确认信号，适合检查止盈、减仓或风险敞口。"
        icon = "🔴"
        title = "JT 逃顶信号"
    else:
        rule = "极端上涨后，Regime Score 在 0 轴上方首次拐头向下。"
        reminder = "这是早期风险信号，建议提高警惕并等待确认。"
        icon = "🟠"
        title = "JT 逃顶信号"

    return "\n".join([
        f"{icon} {title}",
        "",
        f"信号: {signal_name}",
        f"币对: {symbol}",
        f"数据源: {source['label']}",
        f"周期: {settings['interval'].upper()}",
        f"信号时间: {utc8_time_text(marker['time'])} UTC+8",
        f"信号收盘价: {marker['close']}",
        f"Regime Score: {score:.2f}",
        "",
        f"规则: {rule}",
        f"提醒: {reminder}",
    ])


def scan_once(now=None):
    now = time.time() if now is None else now
    monitor = read_json_file(MONITOR_FILE, None)
    if not monitor:
        write_status({
            "state": "idle",
            "last_scan_at": int(now),
            "message": "等待页面同步后台配置",
        })
        return

    settings = monitor["settings"]
    sent_key_list = [
        key for key in monitor.get("sent_signal_keys", [])
        if isinstance(key, str)
    ]
    sent_keys = set(sent_key_list)
    sent_channel_key_list = [
        key for key in monitor.get("sent_signal_channel_keys", [])
        if isinstance(key, str)
    ]
    sent_channel_keys = set(sent_channel_key_list)
    sent_this_scan = []

    for symbol in monitor.get("enabled_symbols", []):
        rows = fetch_remote_klines(settings["marketType"], symbol, settings["interval"], settings["limit"])
        candles = closed_candles(rows_to_candles(rows), settings["interval"], now)
        if not candles:
            continue

        oscillator = calculate_jt_regime_oscillator(candles, settings)
        markers = build_signal_markers(oscillator, candles)
        baseline = int(monitor.get("baseline", {}).get(symbol, 0))
        candidates = [
            marker for marker in markers
            if marker["time"] > baseline and marker["text"] in {"试探抄底", "确认抄底", "试探逃顶", "确认逃顶"}
        ]

        for marker in candidates:
            key = signal_key(marker, settings, symbol)
            if key in sent_keys:
                continue
            message = marker_message(marker, settings, symbol)
            pending_channels = [
                channel for channel in configured_notification_channels()
                if f"{channel}:{key}" not in sent_channel_keys
            ]
            results = send_notification(
                message,
                title=f"{marker['text']} {symbol}",
                priority="high",
                idempotency_key=key,
                group_key=f"jt-regime-{symbol}",
                data={
                    "symbol": symbol,
                    "signal": marker["text"],
                    "time": marker["time"],
                    "marketType": settings["marketType"],
                    "interval": settings["interval"],
                },
                channels=pending_channels,
            )

            for channel, result in results.items():
                if result.get("ok"):
                    channel_key = f"{channel}:{key}"
                    sent_channel_keys.add(channel_key)
                    sent_channel_key_list.append(channel_key)

            if all(result.get("ok") for result in results.values()):
                sent_keys.add(key)
                sent_key_list.append(key)
                sent_this_scan.append({
                    "symbol": symbol,
                    "signal": marker["text"],
                    "time": marker["time"],
                    "key": key,
                    "channels": sorted(results),
                })

            monitor["sent_signal_keys"] = sent_key_list[-1000:]
            monitor["sent_signal_channel_keys"] = sent_channel_key_list[-2000:]
            atomic_write_json(MONITOR_FILE, monitor)

            failed = {
                channel: result.get("error", "returned false")
                for channel, result in results.items()
                if not result.get("ok")
            }
            if failed:
                raise RuntimeError(f"notification channel failure for {key}: {failed}")

    write_status({
        "state": "ok",
        "last_scan_at": int(now),
        "message": "扫描完成",
        "last_error": "",
        "traceback": "",
        "last_sent": sent_this_scan[-10:],
        "sent_signal_count": len(sent_keys),
    })


def write_status(status):
    current = read_json_file(WORKER_STATUS_FILE, {})
    current.update(status)
    atomic_write_json(WORKER_STATUS_FILE, current)


def main():
    print(f"JT Regime worker started; scan interval={SCAN_SECONDS}s; config={MONITOR_FILE}")
    while True:
        try:
            scan_once()
        except Exception as exc:
            write_status({
                "state": "error",
                "last_scan_at": int(time.time()),
                "last_error": str(exc),
                "traceback": traceback.format_exc(limit=6),
            })
            print(f"scan failed: {exc}", flush=True)
        time.sleep(SCAN_SECONDS)


if __name__ == "__main__":
    main()
