# JT Chart

JT Chart 是一个面向加密货币行情的 JT Regime 图表与抄底信号项目。项目提供 Web/Docker 运行形态和 Android APK 运行形态，二者共享同一套核心图表语义：价格 K 线、JT Regime 柱状图、熊市 WMA、熊市填充和抄底标记。

## 项目形态

### Web 图表与后台通知

Web 形态由 Python HTTP 服务、单页 Lightweight Charts 图表和后台 Worker 组成：

- `jt-regime-chart` 提供图表页、行情 API、通知测试接口和监控配置同步接口。
- `jt-regime-worker` 按同步后的监控配置扫描行情，并把正式信号投递到通知通道。
- 图表页入口是 `http://127.0.0.1:8088/`，根路径会打开 `jt-regime-oscillator.html`。
- 运行数据保存在 Docker volume `jt-regime-data`，后台监控配置写入 `/data/monitor.json`。

### Android APK

Android 形态位于 `android-app/`，是一个可独立安装的 JT Chart App：

- Kotlin + Jetpack Compose 承载原生外壳、设置抽屉、标题、加载态和失败态。
- APK 内置 WebView 承载 Lightweight Charts 和现有 JavaScript 图表算法。
- App 直接访问六种公共行情源：Binance 现货、Binance U 本位、Bybit 现货、Bybit USDT 永续、Bitget 现货、Bitget USDT 永续。
- 设置、最后成功行情、图表 viewport 和缓存快照都保存在本机。
- 前台负责实时请求，回前台触发一次刷新，旋转和后台状态保持当前图表语义。

## 核心能力

- 展示币对、数据源、周期、K 线数量和更新时间。
- 绘制价格 K 线、JT Regime、熊市 WMA 白线、熊市绿色填充、确认/试探抄底标记。
- 支持 `1m`、`5m`、`15m`、`1h`、`4h`、`1d`、`1w` 周期。
- 支持 `BTCUSDT`、`ETHUSDT`、`SOLUSDT`、`BNBUSDT`、`XRPUSDT`、`DOGEUSDT`、`PENDLEUSDT`、`MONUSDT` 等默认币对。
- 支持算法参数调整：Lag Start、Lag End、Z 窗口、极端阈值、平滑、熊市 WMA 周期。
- 支持缓存优先展示、失败保留旧图、请求身份和展示身份分离。

## 目录结构

```text
.
├── chart_server.py              # Web 图表服务和 HTTP API
├── jt_regime_worker.py          # 后台正式通知 Worker
├── jt_shared.py                 # 行情、算法、通知和共享工具
├── jt-regime-oscillator.html    # Web 图表页
├── docker-compose.yml           # Web 服务和 Worker 编排
├── STARTUP.md                   # Web/Docker 启动说明
├── HANDOFF.md                   # 当前 Android 真机调试交接
├── tests/                       # Python 回归测试
└── android-app/                 # Android APK 工程
```

## Web/Docker 启动

```sh
docker compose build
docker compose up -d
```

访问图表：

```text
http://127.0.0.1:8088/
```

查看后台监控状态：

```sh
curl -sS http://127.0.0.1:8088/api/monitor/status
```

停止服务：

```sh
docker compose down
```

## Android 开发入口

```sh
cd android-app
./gradlew assembleDebug
```

Android MVP 的行为规格集中在 `android-app/MVP_SPEC.md`。当前真机调试前沿集中在 `HANDOFF.md`，其中记录了候选 APK、设备 smoke 结果、网络环境结论和后续可选验证项。

## 验证命令

Web/Python 回归：

```sh
python3 -m unittest discover -s tests
python3 -m py_compile chart_server.py jt_shared.py jt_regime_worker.py tests/test_jt_regime.py
```

Android 构建：

```sh
cd android-app
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## 配置与安全

本地通知密钥和代理配置通过 `.env` 或本地配置文件注入。仓库通过 `.gitignore` 保持密钥文件、通知 token、设备连接信息、APK 调试证据和本地构建产物留在本机。

WeCom 通道和 ElectricWave 通道由 Docker 环境变量启用。通知测试接口和正式 Worker 都会返回通道级结果，便于确认投递状态。

## 继续开发

- Web 图表和后台通知改动以 `STARTUP.md`、`chart_server.py`、`jt_shared.py`、`jt_regime_worker.py` 和 `tests/` 为主要入口。
- Android App 改动以 `android-app/MVP_SPEC.md`、`android-app/app/src/main/` 和 `android-app/app/src/test/` 为主要入口。
- 当前 Android 真机调试延续以 `HANDOFF.md` 为入口，先复核已有证据，再执行新的设备验证或源码改动。
