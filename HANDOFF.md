# JT Chart 无线 ADB 真机调试交接

> 更新时间：2026-07-15 00:00（Asia/Shanghai）。本文件已围绕"上一轮真机 smoke 已完成、根因已定性"覆盖旧交接。不要重做 Android MVP、不要重新规划产品、不要重新诊断六源失败原因（已定性为网络/GFW，非 App 缺陷）。

## 上一轮结论（已闭环）

在 **新无线设备 Xiaomi Redmi K40 Gaming（model `M2012K10C`，HyperOS OS1.0/V816，Android 13/SDK 33，单屏 1080x2400）** 上对**同一候选 debug APK**（SHA-256 不变：`1f881815a93999d908a13dc523ed4f2e7a845cc70f1b9f3c961bf41d25c191be`）完成：

1. **六源 smoke + 根因定位**：只有 `Binance 现货`（`data-api.binance.vision`）成功（500 根）。其余失败，且**根因是网络层（GFW），不是 App**。设备本次走**中国移动 5G 蜂窝、无 VPN**（旧 HANDOFF 那台是 Wi-Fi+VPN，所以 Bybit 能通——同 APK 不同网络）。逐 host 用设备 `nc -w 6 <host> 443` 探活（比 ping 强），两次复测一致：
   - `data-api.binance.vision`：TCP 通 → 成功。
   - `fapi.binance.com`（Binance U本位）：TCP 不通 → Timeout。
   - `api.bybit.com`：DNS 被污染成 Dropbox 段（`162.125.17.131`）+ TCP 不通 → Timeout。
   - `api.bitget.com`：DNS 干净（Cloudflare）+ TCP 通，但 HTTPS 快速失败（TLS 握手被 SNI-RST）→ Connectivity（几秒）。
2. **生命周期 smoke（PASS）**：旋转（保留旧图/查询/时间戳、旋转不发请求、自动旋转已恢复）、HOME 后台（7s 内 0 请求）、回前台（一次刷新脉冲）、`am kill` 进程重建（进程死→冷启→已应用设置 + 同键 Room 缓存恢复，BTCUSDT/现货 500 根无失败）、失败保旧图（六源里每次失败都保留旧图 + 双身份气泡）。**未测**：锁屏/唤醒（需用户解锁，无线 ADB 在锁屏不可靠）、断网 smoke（蜂窝单连设备，断网会同时断无线调试）。
3. **崩溃/ANR**：0。冷启正常，进程全程存活。`exit-info` 空。
4. **可诊断性缺口（非功能性缺陷）**：App 在请求边界**完全没有日志**——`app/src` grep 不到 `Log.*`/`Timber`/`HttpLoggingInterceptor`/`Interceptor`/`EventListener`。`OkHttpMarketTransport.onFailure` 把 `IOException` 吞成 `MarketError.Timeout`/`Connectivity` 不打日志。失败窗口的系统 logcat 里 0 行 okhttp/host/异常栈。唯一的网络痕迹是 OS 自动产生的 `TrafficStats tagSocket(...)` 和 `System.out [com.mediatek.cta.CtaAdapter]:check permission begin!`（联发科 CTA 网络闸门，此处放行）。详见证据 `request-logging-finding.md`。

**结论：不需要为六源失败改源码**（HANDOFF「何时允许改源码」门槛未达到：失败是环境性、不可在 App 侧复现）。`applyDraft()` → DataStore 持久化经干净复现验证正常（显示==落盘）。

## 候选 APK（未变）

```text
路径：android-app/app/build/outputs/apk/debug/app-debug.apk
applicationId：com.makia.jtchart   versionName/Code：0.1.0 / 1   minSdk 29 / targetSdk 36
SHA-256：1f881815a93999d908a13dc523ed4f2e7a845cc70f1b9f3c961bf41d25c191be
```

设备为 debug APK，**不是** HyperOS 发布门禁：正式门禁要求 Pixel 的 release 签名 APK。

## 本轮证据（已 gitignore，脱敏）

```text
android-app/qa-evidence/0.1.0-c1/20260714T150058Z-4ec37fb-redmik40gaming-hyperos13-debug/
  QA-SUMMARY.md · manifest.json · device-baseline.txt · install-result.txt
  network-probe.txt · six-source-summary.md · lifecycle-summary.md
  request-logging-finding.md · final-app-logcat-filtered.txt · crash-anr-scan.txt(0 行)
  device-state-restored.txt · shot_01..07_*.png · lc_01..06b_*.png · SHA256SUMS.txt（25 件全校验 OK）
```

不含 raw serial / 无线 endpoint / 设备 IP/MAC/SSID / 整批 K 线 / 上游完整响应。交易所 host 的解析 IP（含被污染的 Dropbox 段）作为关键诊断证据保留（属公网基础设施）。

## 现场保护

仓库：`/Volumes/samsung_disk_2T/openclaw_workspace/jt-chart`，HEAD `4ec37fb`。

```bash
cd /Volumes/samsung_disk_2T/openclaw_workspace/jt-chart
git status --short   # 预期：M HANDOFF.md；M tests/__pycache__/test_jt_regime.cpython-313.pyc（用户既有，禁动）
```

- 不读取或输出 `.env`；不记录 keystore / 密码 / 私钥 / 设备 serial / 无线 endpoint / SSID/BSSID/IP/MAC。
- `tests/__pycache__/test_jt_regime.cpython-313.pyc` 是用户既有改动，禁止还原/覆盖/提交。
- 没有明确源码缺陷前不要重构、不要改 host、不要加代理、不要换数据源。
- 设备当前已留在良好状态：BTCUSDT / Binance 现货 / 1h / 500（PID 见证据），未留在失败界面，未清数据/卸载。

## 设备连接（每会话动态选择，勿硬编码/落盘 serial）

```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" devices -l
SERIAL=$("$ADB" devices -l | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
```

选 `state=device` 且非 `emulator-*` 的设备。可能同时出现其他真机/模拟器，动态选。设备是无线路径（`_adb-tls-connect._tcp`），只保存在 shell 变量。

## 下一位 agent 可做（按优先级，均非阻塞）

### A.（可选）补两项目前无法自动化的 smoke
- **锁屏/唤醒**：需用户协助解锁（无线 ADB 在锁屏下不可靠）。建议设备端录屏 + 用户恢复。
- **断网 smoke**：蜂窝单连设备，不能像 Pixel 那样断 Wi-Fi；断蜂窝会断无线调试。先确认 HyperOS 是否支持按 App 断网；或由用户切到可达网络（VPN/海外）后复测。

### B.（可选）在可达网络上跑全六源 + release 门禁
- 当前设备（移动蜂窝无 VPN）只能验证 Binance 现货。要在真机验证六源全通，需把设备接到可达网络（VPN 或旧 HANDOFF 的 Wi-Fi+VPN 环境）。
- 真正的 HyperOS 发布门禁仍需 **Pixel 的 release 签名 APK**，不是当前 debug APK。

### C.（可选，需明确授权 + 重打包）补 debug 请求日志
诊断增强，非功能性修复。建议：
- `MarketDataFactory.createClient()` 在 debug 构建加 `HttpLoggingInterceptor`（BASIC/HEADERS，勿 BODY 以免落盘整批 K 线）。
- `OkHttpMarketTransport.onFailure` 加 `Log.w(TAG,"request ${request.url} failed", e)`。
- `MarketRepositoryImpl.fetch` 记 host/attempt/终态。
改源码后必须重跑 JVM + lint + assembleDebug + 受影响真机回归；APK 哈希变化后同步更新本文件与证据，旧哈希不得继续引用。若做此项，建议同时新增一条覆盖「applyDraft 持久化」的 JVM 回归（见下方经验）。

## UI 自动化经验（本设备，单屏）

- 设备为单屏（displayId=0），普通 `input tap`/DOWN-UP 可靠；抽屉是**滚动列表**：上半是币对、下半是数据源、底部周期/数量，「应用并刷新」按钮**固定在底部**不随滚动。
- **必须每次点击前重新 `uiautomator dump`**，按 `content-desc` 取 bounds 算中心点再点。币对行（`选择币对 X`）紧贴数据源行（`选择数据源 X`）上方，**用旧 dump 的 bounds 点击会选错币对**——本轮一度因此把 `currentSymbol` 误选成 `MONUSDT`（已查清为自动化干扰，非 App 缺陷）。点完 apply 后要校验**完整标题（含币对）**，不能只看 source。
- 可用语义词：`打开设置` `关闭导航菜单` `选择币对 <SYM>` `选择数据源 Binance 现货/Binance U本位/Bybit 现货/Bybit USDT 永续/Bitget 现货/Bitget USDT 永续` `应用设置并按需刷新` `取消设置修改` `立即刷新行情` `请求失败，点击重试` `当前图表数据标题`。
- 标题格式：成功 `BTCUSDT · Binance 现货 · 1h · 500`（`·` 分隔，display name）；失败 `BTCUSDT / bybitSpot / 1h · 请求超时，当前仍显示 BTCUSDT / spot / 1h 数据 · 点击重试`（`/` 分隔，用 source key，带双身份）。
- 复用脚本：上一轮的 `/tmp/adb_ui.sh`（content-desc 点击 + poll_source，zsh 下用 `${=b}` 拆词、变量避开 `status` 只读名）。注意 zsh 的 `status` 是只读变量，勿作局部变量名。

## 网络诊断经验（本设备）

- 设备有 `/system/bin/nc`（toybox），**没有**可靠的 curl/wget。用 `nc -w 6 <host> 443 < /dev/null`（exit 0=通）做 TCP 探活，远强于 ping。ping 只用来读解析 IP / NXDOMAIN，**不要用 ping 可达性下结论**。
- 读 DataStore 落盘值（脱敏，仅 symbol/source/算法参数，非行情）：`adb exec-out run-as com.makia.jtchart cat files/datastore/jt-chart-settings.pb | strings`。
- 六源失败分类对应：`Timeout`=`SocketTimeoutException`（TCP 连不上/读超时，双超时约 40s）；`Connectivity`=其他 `IOException`（含 TLS-RST，快速几秒）。OkHttp 超时：connect 10s / read 15s / call 20s，Timeout/Connectivity 会重试一次。

## 必读真值源

- 行为规格：[android-app/MVP_SPEC.md](android-app/MVP_SPEC.md)
- 完整矩阵：[.issues/wayfinder/android-app/assets/端到端验收矩阵.md](.issues/wayfinder/android-app/assets/端到端验收矩阵.md)
- 旧 Pixel 证据结构参考：[android-app/qa-evidence/0.1.0-c1/20260714T065708Z-33fbab3-pixel9proxl-api36/](android-app/qa-evidence/0.1.0-c1/20260714T065708Z-33fbab3-pixel9proxl-api36/)（另一 APK 哈希，仅参考结构）

## Suggested skills

- `$implement`：仅当授权做 C（debug 日志）等明确源码改动时，按 `MVP_SPEC.md` 做最小修改。
- `$code-review`：若有源码变更，以 `4ec37fb` 为固定点做 Standards/Spec 双轴复核。
- `$verify`：源码变更后端到端验证。

不要重新使用 `$wayfinder`、重做原型或重复已关闭的产品/API 研究，除非用户明确扩大范围。
