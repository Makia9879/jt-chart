import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import jt_shared
from jt_shared import (
    atomic_write_json,
    build_bottom_markers,
    build_signal_markers,
    build_top_markers,
    calculate_jt_regime_oscillator,
    closed_candles,
    read_json_file,
    signal_key,
)


PAGE_PATH = Path(__file__).resolve().parents[1] / "jt-regime-oscillator.html"


def javascript_function_source(name):
    page = PAGE_PATH.read_text(encoding="utf-8")
    match = re.search(rf"function\s+{re.escape(name)}\s*\([^)]*\)\s*\{{", page)
    if not match:
        raise AssertionError(f"JavaScript function not found: {name}")

    depth = 0
    for index in range(match.end() - 1, len(page)):
        if page[index] == "{":
            depth += 1
        elif page[index] == "}":
            depth -= 1
            if depth == 0:
                return page[match.start():index + 1]

    raise AssertionError(f"JavaScript function is incomplete: {name}")


def fixture_candles():
    price = 100.0
    candles = []
    for index in range(80):
        if index < 30:
            price += 0.1
        elif index < 40:
            price -= 0.1
        elif index == 40:
            price -= 10
        elif index < 43:
            price -= 2
        elif index < 48:
            price += 1
        elif index < 54:
            price += 4
        else:
            price += 0.1
        close = round(price, 4)
        candles.append({
            "time": 1700000000 + index * 3600,
            "open": close,
            "high": close + 0.3,
            "low": close - 0.3,
            "close": close,
        })
    return candles


class JTRegimeTests(unittest.TestCase):
    def test_bear_market_overlay_matches_pine_wma_and_condition(self):
        if shutil.which("node") is None:
            self.skipTest("node is not installed")

        candles = [
            {"time": 1, "close": 10},
            {"time": 2, "close": 11},
            {"time": 3, "close": 9},
            {"time": 4, "close": 8},
            {"time": 5, "close": 12},
        ]
        script = "\n".join([
            javascript_function_source("wma"),
            javascript_function_source("calculateBearMarketOverlay"),
            "console.log(JSON.stringify(calculateBearMarketOverlay(JSON.parse(process.argv[1]), 3)));",
        ])
        result = subprocess.run(
            ["node", "-e", script, json.dumps(candles)],
            check=True,
            capture_output=True,
            text=True,
        )

        overlay = json.loads(result.stdout)
        self.assertEqual([item["time"] for item in overlay], [3, 4, 5])
        self.assertAlmostEqual(overlay[0]["wma"], 59 / 6)
        self.assertAlmostEqual(overlay[1]["wma"], 53 / 6)
        self.assertAlmostEqual(overlay[2]["wma"], 61 / 6)
        self.assertEqual([item["isBear"] for item in overlay], [True, True, False])

    def test_bear_market_period_requires_more_candles_than_wma_length(self):
        if shutil.which("node") is None:
            self.skipTest("node is not installed")

        script = "\n".join([
            javascript_function_source("validateBearMarketSettings"),
            "console.log(JSON.stringify([",
            "  validateBearMarketSettings({ limit: 200, bearWmaLength: 200 }),",
            "  validateBearMarketSettings({ limit: 201, bearWmaLength: 200 }),",
            "  validateBearMarketSettings({ limit: 500, bearWmaLength: 200 }, 120)",
            "]));",
        ])
        result = subprocess.run(
            ["node", "-e", script],
            check=True,
            capture_output=True,
            text=True,
        )

        errors = json.loads(result.stdout)
        self.assertEqual(errors[0], "K线数量必须大于熊市 WMA 周期")
        self.assertEqual(errors[1], "")
        self.assertEqual(errors[2], "行情仅返回 120 根 K 线，无法计算 WMA 200")

    def test_formula_matches_page_semantics_for_scores_and_markers(self):
        settings = {
            "marketType": "spot",
            "interval": "1h",
            "limit": 200,
            "momStart": 2,
            "momEnd": 10,
            "zLength": 10,
            "extThresh": 1.5,
            "smoothLen": 4,
        }
        oscillator = calculate_jt_regime_oscillator(fixture_candles(), settings)
        markers = build_bottom_markers(oscillator, fixture_candles())

        self.assertEqual(oscillator[0]["index"], 3)
        self.assertEqual(oscillator[0]["value"], 0)

        indexed = {item["index"]: item for item in oscillator}
        self.assertAlmostEqual(indexed[39]["value"], -1.265519, places=6)
        self.assertAlmostEqual(indexed[40]["z1"], -3.0, places=6)
        self.assertTrue(indexed[40]["isExtremeDown"])
        self.assertAlmostEqual(indexed[47]["value"], 0.061238, places=6)

        marker_summary = [
            (marker["text"], marker["time"], round(marker["score"], 6), marker["close"])
            for marker in markers[:2]
        ]
        self.assertEqual(marker_summary, [
            ("试探抄底", 1700129600, -2.790339, 102.3),
            ("确认抄底", 1700169200, 0.061238, 93.0),
        ])

    def test_top_markers_are_emitted_as_exit_signals(self):
        candles = [
            {
                "time": 1700000000 + index * 3600,
                "high": 110 - index,
                "low": 100 - index,
                "close": 105 - index,
            }
            for index in range(12)
        ]
        candles[8]["close"] = 90
        oscillator = [
            {"index": 0, "time": candles[0]["time"], "value": 0.1, "isExtremeUp": False, "isExtremeDown": False, "close": candles[0]["close"]},
            {"index": 1, "time": candles[1]["time"], "value": 0.2, "isExtremeUp": False, "isExtremeDown": False, "close": candles[1]["close"]},
            {"index": 2, "time": candles[2]["time"], "value": 0.5, "isExtremeUp": True, "isExtremeDown": False, "close": candles[2]["close"]},
            {"index": 3, "time": candles[3]["time"], "value": 0.8, "isExtremeUp": False, "isExtremeDown": False, "close": candles[3]["close"]},
            {"index": 4, "time": candles[4]["time"], "value": 0.6, "isExtremeUp": False, "isExtremeDown": False, "close": candles[4]["close"]},
            {"index": 5, "time": candles[5]["time"], "value": 0.4, "isExtremeUp": False, "isExtremeDown": False, "close": candles[5]["close"]},
            {"index": 6, "time": candles[6]["time"], "value": 0.2, "isExtremeUp": False, "isExtremeDown": False, "close": candles[6]["close"]},
            {"index": 7, "time": candles[7]["time"], "value": 0.1, "isExtremeUp": False, "isExtremeDown": False, "close": candles[7]["close"]},
            {"index": 8, "time": candles[8]["time"], "value": -0.2, "isExtremeUp": False, "isExtremeDown": False, "close": candles[8]["close"]},
        ]

        markers = build_top_markers(oscillator, candles)

        self.assertEqual(
            [(marker["text"], marker["position"], marker["shape"], marker["type"], marker["strength"]) for marker in markers],
            [
                ("试探逃顶", "aboveBar", "arrowDown", "topTentative", "tentative"),
                ("确认逃顶", "aboveBar", "arrowDown", "topConfirmed", "confirmed"),
            ],
        )
        self.assertEqual(build_signal_markers(oscillator, candles)[-1]["text"], "确认逃顶")

    def test_python_formula_matches_javascript_page_semantics(self):
        if shutil.which("node") is None:
            self.skipTest("node is not installed")

        settings = {
            "marketType": "spot",
            "interval": "1h",
            "limit": 200,
            "momStart": 2,
            "momEnd": 10,
            "zLength": 10,
            "extThresh": 1.5,
            "smoothLen": 4,
        }
        candles = fixture_candles()
        python_oscillator = calculate_jt_regime_oscillator(candles, settings)
        python_markers = build_bottom_markers(python_oscillator, candles)
        script = r"""
const input = JSON.parse(process.argv[1]);
const candles = input.candles;
const settings = input.settings;
function sma(values, length, index) {
  if (index < length - 1) return null;
  let sum = 0;
  for (let i = index - length + 1; i <= index; i++) {
    if (values[i] == null || !Number.isFinite(values[i])) return null;
    sum += values[i];
  }
  return sum / length;
}
function stdev(values, length, index) {
  const mean = sma(values, length, index);
  if (mean == null) return null;
  let sumSq = 0;
  for (let i = index - length + 1; i <= index; i++) {
    if (values[i] == null || !Number.isFinite(values[i])) return null;
    const delta = values[i] - mean;
    sumSq += delta * delta;
  }
  return Math.sqrt(sumSq / length);
}
function zscore(values, length, index) {
  const mean = sma(values, length, index);
  const sd = stdev(values, length, index);
  if (mean == null || sd == null || sd === 0) return 0;
  return (values[index] - mean) / sd;
}
function wma(values, length, index) {
  if (index < length - 1) return null;
  let weighted = 0;
  let weightSum = 0;
  for (let offset = 0; offset < length; offset++) {
    const value = values[index - offset];
    if (value == null || !Number.isFinite(value)) return null;
    const weight = length - offset;
    weighted += value * weight;
    weightSum += weight;
  }
  return weighted / weightSum;
}
function calculateJTRegimeOscillator(candles, settings) {
  const closes = candles.map((candle) => candle.close);
  const ret1 = new Array(candles.length).fill(null);
  const retMom = new Array(candles.length).fill(null);
  const rawScore = new Array(candles.length).fill(null);
  const output = [];
  for (let i = 0; i < candles.length; i++) {
    if (i >= settings.zLength + settings.momEnd) {
      ret1[i] = (closes[i] - closes[i - 1]) / closes[i - 1];
      retMom[i] = (closes[i - (settings.momStart - 1)] - closes[i - settings.momEnd]) / closes[i - settings.momEnd];
    }
    const z1 = zscore(ret1, settings.zLength, i);
    const zMom = zscore(retMom, settings.zLength, i);
    const isExtremeUp = z1 > settings.extThresh;
    const isExtremeDown = z1 < -settings.extThresh;
    const shortTermFactor = isExtremeUp || isExtremeDown ? -z1 : z1;
    rawScore[i] = zMom + shortTermFactor;
    const score = wma(rawScore, settings.smoothLen, i);
    if (score != null) {
      output.push({ index: i, time: candles[i].time, value: score, close: candles[i].close, high: candles[i].high, low: candles[i].low, z1, zMom, isExtremeUp, isExtremeDown });
    }
  }
  return output;
}
function buildBottomMarkers(oscillator, candles) {
  const markers = [];
  const recentExtremeLookback = 12;
  const breakoutLookback = 5;
  const markerCooldown = 8;
  let lastMarkerIndex = -Infinity;
  for (let i = 2; i < oscillator.length; i++) {
    const current = oscillator[i];
    const prev = oscillator[i - 1];
    const prev2 = oscillator[i - 2];
    const recentExtremeDown = oscillator.slice(Math.max(0, i - recentExtremeLookback), i + 1).some((item) => item.isExtremeDown);
    if (!recentExtremeDown) continue;
    const stillBelowZero = current.value < 0;
    const crossedAboveZero = prev.value <= 0 && current.value > 0;
    const firstTurnUp = prev.value <= prev2.value && current.value > prev.value;
    const candleIndex = current.index;
    const from = Math.max(0, candleIndex - breakoutLookback);
    const priorHigh = Math.max(...candles.slice(from, candleIndex).map((candle) => candle.high));
    const reclaimedShortStructure = Number.isFinite(priorHigh) && current.close > priorHigh;
    if (crossedAboveZero && reclaimedShortStructure) {
      markers.push({ time: current.time, position: "belowBar", color: "#4e8cff", shape: "arrowUp", text: "确认抄底", score: current.value, close: current.close });
      lastMarkerIndex = i;
      continue;
    }
    if (stillBelowZero && firstTurnUp && i - lastMarkerIndex >= markerCooldown) {
      markers.push({ time: current.time, position: "belowBar", color: "#ffcc00", shape: "arrowUp", text: "试探抄底", score: current.value, close: current.close });
      lastMarkerIndex = i;
    }
  }
  return markers;
}
const oscillator = calculateJTRegimeOscillator(candles, settings);
const markers = buildBottomMarkers(oscillator, candles);
console.log(JSON.stringify({ oscillator, markers }));
"""
        result = subprocess.run(
            ["node", "-e", script, json.dumps({"candles": candles, "settings": settings})],
            check=True,
            capture_output=True,
            text=True,
        )
        js = json.loads(result.stdout)

        self.assertEqual(len(js["oscillator"]), len(python_oscillator))
        for index in [3, 39, 40, 47]:
            py_item = next(item for item in python_oscillator if item["index"] == index)
            js_item = next(item for item in js["oscillator"] if item["index"] == index)
            self.assertAlmostEqual(py_item["value"], js_item["value"], places=10)
            self.assertAlmostEqual(py_item["z1"], js_item["z1"], places=10)
            self.assertAlmostEqual(py_item["zMom"], js_item["zMom"], places=10)
            self.assertEqual(py_item["isExtremeDown"], js_item["isExtremeDown"])

        self.assertEqual(
            [(item["text"], item["time"], round(item["score"], 10)) for item in python_markers],
            [(item["text"], item["time"], round(item["score"], 10)) for item in js["markers"]],
        )

    def test_closed_candles_excludes_candles_not_closed_for_10_seconds(self):
        candles = [
            {"time": 1000, "open": 1, "high": 1, "low": 1, "close": 1},
            {"time": 1060, "open": 2, "high": 2, "low": 2, "close": 2},
            {"time": 1120, "open": 3, "high": 3, "low": 3, "close": 3},
        ]

        self.assertEqual(
            [candle["time"] for candle in closed_candles(candles, "1m", now_seconds=1129)],
            [1000],
        )
        self.assertEqual(
            [candle["time"] for candle in closed_candles(candles, "1m", now_seconds=1130)],
            [1000, 1060],
        )

    def test_signal_key_includes_signal_type_for_deduplication(self):
        settings = {"marketType": "spot", "interval": "1h"}
        trial = {"time": 1700000000, "text": "试探抄底"}
        confirmed = {"time": 1700000000, "text": "确认抄底"}

        self.assertEqual(signal_key(trial, settings, "BTCUSDT"), "spot:BTCUSDT:1h:1700000000:试探抄底")
        self.assertNotEqual(
            signal_key(trial, settings, "BTCUSDT"),
            signal_key(confirmed, settings, "BTCUSDT"),
        )

    def test_atomic_json_write_round_trips_valid_json(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "monitor.json"
            atomic_write_json(path, {"sent_signal_keys": ["a"], "nested": {"ok": True}})

            self.assertEqual(
                read_json_file(path, {}),
                {"sent_signal_keys": ["a"], "nested": {"ok": True}},
            )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["sent_signal_keys"], ["a"])

    def test_electricwave_sender_posts_contract_payload(self):
        response = mock.Mock(status_code=202, text='{"status":"queued"}')
        with (
            mock.patch.object(jt_shared, "ELECTRICWAVE_TOKEN", "secret-token"),
            mock.patch.object(jt_shared, "ELECTRICWAVE_ENDPOINT", "https://notice.example/api/v1/notifications"),
            mock.patch.object(jt_shared, "ELECTRICWAVE_RECEIVER_ID", "phone-main"),
            mock.patch.object(jt_shared.requests, "post", return_value=response) as post,
        ):
            self.assertTrue(jt_shared.send_electricwave(
                "标题行\n正文",
                idempotency_key="spot:BTCUSDT:1h:1700000000:试探抄底",
                group_key="jt-regime-BTCUSDT",
                data={"symbol": "BTCUSDT"},
            ))

        kwargs = post.call_args.kwargs
        self.assertEqual(post.call_args.args[0], "https://notice.example/api/v1/notifications")
        self.assertEqual(kwargs["headers"]["Authorization"], "Bearer secret-token")
        self.assertEqual(kwargs["json"]["receiver_id"], "phone-main")
        self.assertEqual(kwargs["json"]["title"], "标题行")
        self.assertEqual(kwargs["json"]["body"], "标题行\n正文")
        self.assertEqual(kwargs["json"]["priority"], "high")
        self.assertEqual(kwargs["json"]["group_key"], "jt-regime-BTCUSDT")
        self.assertEqual(kwargs["json"]["data"], {"symbol": "BTCUSDT"})

    def test_electricwave_sender_falls_back_to_openssl_without_token_in_command(self):
        response = mock.Mock(stdout=b"HTTP/1.1 202 Accepted\r\n\r\n", stderr=b"")
        with (
            mock.patch.object(jt_shared, "ELECTRICWAVE_TOKEN", "secret-token"),
            mock.patch.object(jt_shared, "ELECTRICWAVE_ENDPOINT", "https://notice.example/api/v1/notifications"),
            mock.patch.object(jt_shared, "ELECTRICWAVE_RECEIVER_ID", "phone-main"),
            mock.patch.object(jt_shared.requests, "post", side_effect=jt_shared.requests.Timeout("timeout")),
            mock.patch.object(jt_shared.subprocess, "run", return_value=response) as run,
        ):
            self.assertTrue(jt_shared.send_electricwave("fallback message"))

        args = run.call_args.args[0]
        self.assertNotIn("secret-token", args)
        self.assertIn(b"Authorization: Bearer secret-token", run.call_args.kwargs["input"])
        self.assertIn(b"POST /api/v1/notifications HTTP/1.1", run.call_args.kwargs["input"])

    def test_notification_fanout_attempts_configured_channels(self):
        with (
            mock.patch.object(jt_shared, "send_wecom", return_value=True) as send_wecom,
            mock.patch.object(jt_shared, "send_electricwave", return_value=True) as send_electricwave,
        ):
            results = jt_shared.send_notification(
                "message",
                title="title",
                idempotency_key="signal-key",
                channels=["wecom", "electricwave"],
            )

        self.assertEqual(results, {"wecom": {"ok": True}, "electricwave": {"ok": True}})
        send_wecom.assert_called_once_with("message")
        send_electricwave.assert_called_once()

    def test_configured_notification_channels_keep_wecom_without_electricwave_token(self):
        with mock.patch.object(jt_shared, "ELECTRICWAVE_TOKEN", ""):
            self.assertEqual(jt_shared.configured_notification_channels(), ["wecom"])
        with mock.patch.object(jt_shared, "ELECTRICWAVE_TOKEN", "secret-token"):
            self.assertEqual(jt_shared.configured_notification_channels(), ["wecom", "electricwave"])


if __name__ == "__main__":
    unittest.main()
