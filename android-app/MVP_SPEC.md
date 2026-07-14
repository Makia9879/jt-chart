# JT Chart Android MVP 规格

> 状态：规划完成，等待实施
> 版本：`0.1.0` / `versionCode = 1`
> applicationId：`com.makia.jtchart`
> 最低系统：Android 10 / API 29

本文是实施的唯一汇总规格。详细算法、网络和验收数字以第 13 节链接资产为证据；若历史原型样例或旧资产的路径/措辞与本文明确口径冲突，以本文为准。当前规划完成不表示 App 已实现或门禁已通过。

## 1. 目标与交付边界

在 `android-app/` 新建一个可独立安装的纯 Android App。App 直接从六种交易所公共行情源获取最近 K 线，在 APK 内置 WebView 中复用现有 JavaScript 算法和 Lightweight Charts，展示价格图、JT Regime、熊市 WMA 与抄底标记。

MVP 必须满足：

- Kotlin + Jetpack Compose 原生外壳；图表页使用 APK 内置 WebView。
- 无自建后端、无 API Key、无账户、无下单、无通知和后台 Worker。
- 设置、最后成功行情和可恢复 viewport 本地持久化。
- 前台实时请求；后台或锁屏不请求，回前台只立即刷新一次。
- 先在 Pixel 9 Pro XL API 36 虚拟机完成开发候选版门禁；同一签名 APK 必须再通过小米 17 Pro Max / HyperOS 真机门禁，才能对外交付。

不在 MVP 内：企业微信/ElectricWave/系统通知、后台监控、自建代理或 Docker 运行依赖、私有 API、云同步、远程配置、应用商店/AAB、原生重写图表，以及平板/折叠屏专属布局。

## 2. 用户可见功能

### 2.1 图表主页

- 页面上方显示实际正在展示的数据身份：币对、数据源、周期、K 线数量及更新时间。
- 价格 K 线图在上，JT Regime 柱状图在下，竖屏和横屏均保持上下排列。
- 显示熊市 WMA 白线、熊市绿色填充、确认/试探抄底标记、Regime 零线和状态摘要。
- 两图 logical range 双向同步；不要求同步十字光标。
- 支持双指缩放、水平平移、双击及横竖屏旋转。

### 2.2 设置抽屉

- 左上角按钮为主入口；仅屏幕左缘 12dp 支持滑出设置抽屉，其余手势归图表。
- Material 3 左侧抽屉采用已确认的 B 方案，按“行情 / 指标 / 刷新”分页。
- 编辑先写入未应用草稿；“应用并刷新”一次提交全部修改，“取消”丢弃本次草稿。
- 草稿编辑不改变 `requestedQuery`、不递增 generation、不联网且不写 DataStore；关闭再打开抽屉时保留进程内草稿，明确点击“取消”才回滚到已应用值。
- 抽屉打开时遮罩图表并禁用 WebView 图表交互；关闭后恢复。
- 草稿跨旋转保留，但 App 关闭或进程重建后丢弃；只有已应用设置才持久化。

### 2.3 设置项与默认值

| 设置 | 默认值 | 校验/选项 |
| --- | --- | --- |
| 币对列表 | `BTCUSDT, ETHUSDT, SOLUSDT, BNBUSDT, XRPUSDT, DOGEUSDT, PENDLEUSDT, MONUSDT` | 去空白并大写，只保留字母数字；每项长度 3..30；去重保序；至少 1 项 |
| 周期 | `1h` | `1m, 5m, 15m, 1h, 4h, 1d, 1w` |
| K 线数量 | `500` | `120..1000`，步长 20 |
| Lag Start / `momStart` | `2` | 取整，最小 2 |
| Lag End / `momEnd` | `52` | 取整，最小 3，且严格大于 `momStart` |
| Z 窗口 / `zLength` | `52` | 取整，最小 10 |
| 极端阈值 / `extThresh` | `2.0` | UI 与持久化校验范围 0.5..5，步长 0.1 |
| 平滑 / `smoothLen` | `8` | 取整，最小 1 |
| 熊市 WMA 周期 | `200` | 取整并夹在 10..990；实返 K 线数须严格大于周期 |
| 数据源 | `spot` | 六种固定 source，见第 4 节 |
| 自动刷新 | `0` | `0, 15, 30, 60, 300` 秒；0 为关闭 |

所有已应用设置和币对顺序均持久化。算法参数变化直接用现有 K 线重算；币对、周期、数据源或数量变化形成新查询。

当请求数量与实返数量一致且 `count <= WMA` 时显示“K线数量必须大于熊市 WMA 周期”；上游少返导致不足时显示“行情仅返回 N 根 K 线，无法计算 WMA L”。

原型只提供上述 B 方案交互结论，不是数据或默认值真值源。原型页面内的 1500 根 limit、缩减周期列表、示例指标值以及“抄底标记/离线缓存”开关均不得带入产品；抄底标记和本地缓存是 MVP 固定能力，不提供关闭开关。

### 2.4 加载、缓存与失败提示

- 原生 UI 必须分别维护 `requestedQuery` 与 `displayedDataset`，标题始终描述实际展示数据。
- 冷启动或新查询时，如有完全匹配缓存，先显示缓存并立即刷新；无同键缓存时可保留上一张成功图。
- 缓存无年龄 TTL，无论多旧都可显示，但必须明确显示“缓存数据 · 更新于 YYYY-MM-DD HH:mm:ss”，不能伪称最新。
- 新查询失败时保留旧图，在顶部显示 App 内小型置顶气泡；不用 Android Toast。
- 气泡同时说明失败查询与实际展示数据，例如“ETH / Bybit / 4h 请求失败，当前仍显示 BTC / Binance / 1h 数据”。
- 气泡持续到该请求成功或用户切换查询，点击立即重试；加载时同一区域可显示 requested/displayed 身份。
- `Cancelled` 不显示为错误；其他稳定错误显示适合用户理解的文案，原始诊断信息只进脱敏本地日志。
- 首次启动既无同键缓存也无上一张图时显示错误空态；气泡只描述 requestedQuery、稳定错误和重试入口，不得虚构 displayedDataset。
- 标题中的 limit 表示 Query 请求数量；状态摘要另列实际返回 count。上游少返不会改变 QueryKey。

## 3. 架构与职责

```text
交易所公共 API
  -> Kotlin Repository（请求、规范化、取消、重试、错误分类）
  -> ViewModel（唯一 UI 状态所有者）
  -> WebMessageChannel（版本化本地通道）
  -> WebView JavaScript（算法、图表、标记和 viewport）
```

### 3.1 Compose / ViewModel

- Compose 负责抽屉、设置控件、标题、新鲜度、加载/失败气泡和图表容器。
- ViewModel 独占已应用设置、草稿、查询 generation、`requestedQuery`、`displayedDataset`、K 线快照、绘制摘要、错误和 viewport。
- 所有手动刷新、自动刷新、前台恢复和设置应用通过同一请求协调器；同一图表最多一个活跃请求。
- 每次已提交的新查询或同查询网络重载递增 request generation，并取消旧请求、重试和 timer；所有网络响应和 Room 写入前再次校验 request generation/query。每次实际发送绘图快照（包括算法参数本地重绘）另递增 `renderRevision`；WebView 发送和 JS 回执校验 pageInstanceId + request generation + renderRevision。草稿编辑两者都不产生。

### 3.2 Repository

- 输入 `Query(symbol, source, interval, limit)`，输出按开盘时间严格升序的规范化 `Candle`。
- 负责固定 host/路径、周期映射、OkHttp、超时、有限重试、取消、envelope 校验、排序去重、DecimalString 校验和稳定错误分类。
- 不依赖 Compose/WebView，不计算指标，不直接操作 UI。
- OHLC/成交量在网络、缓存和跨桥前保持原始十进制字符串，并用 `BigDecimal` 校验；不得提前转为 `Float`/`Double`。词法只接受普通非负十进制 ASCII `^[0-9]+(?:\.[0-9]+)?$`，价格还须严格大于 0；拒绝符号、指数、空白、NaN/Infinity，且不得重格式化，以保留尾随零和稳定 fingerprint。

### 3.3 本地持久化

- Proto DataStore：schema version、币对列表/顺序、当前币对、source、周期、limit、全部算法参数和自动刷新。
- Room：最后成功 K 线快照及匹配 viewport。
- JavaScript 禁止使用 `localStorage`、`sessionStorage` 或其他 Web Storage。

### 3.4 WebView JavaScript

- 只负责 DecimalString 精度推断、转 JS `Number`、JT Regime/WMA/标记计算、Lightweight Charts 绘制、熊市 canvas、logical range 同步及 viewport 回报/恢复。
- 从现有页面迁移算法和绘图代码；删除 CDN、`fetch`、设置 DOM、通知/worker、自动刷新 timer 和 Web Storage。
- JavaScript 使用 IEEE-754 binary64 语义，不把指标重写到 Kotlin。

## 4. 六数据源直连契约

统一输入为 `BTCUSDT` 形式的 `symbol`、七种 interval 和 `120..1000` 的 limit。六个 source 只允许以下四个官方 host，不允许用户编辑 base URL：

| source | 请求 | 固定参数 |
| --- | --- | --- |
| `spot` | `https://data-api.binance.vision/api/v3/klines` | 无 |
| `futures` | `https://fapi.binance.com/fapi/v1/klines` | 无 |
| `bybitSpot` | `https://api.bybit.com/v5/market/kline` | `category=spot` |
| `bybitLinear` | 同上 | `category=linear` |
| `bitgetSpot` | `https://api.bitget.com/api/v2/spot/market/candles` | 无 |
| `bitgetFutures` | `https://api.bitget.com/api/v2/mix/market/candles` | `productType=USDT-FUTURES`, `kLineType=MARKET` |

周期映射：

| App | Binance | Bybit | Bitget Spot | Bitget Futures |
| --- | --- | --- | --- | --- |
| `1m` | `1m` | `1` | `1min` | `1m` |
| `5m` | `5m` | `5` | `5min` | `5m` |
| `15m` | `15m` | `15` | `15min` | `15m` |
| `1h` | `1h` | `60` | `1h` | `1H` |
| `4h` | `4h` | `240` | `4h` | `4H` |
| `1d` | `1d` | `D` | `1day` | `1D` |
| `1w` | `1w` | `W` | `1week` | `1W` |

统一 Candle：

```text
openTimeMs: Long
open/high/low/close: DecimalString
baseVolume/quoteVolume: DecimalString?
```

- 所有适配器显式排序并按 `openTimeMs` 去重，不依赖厂商返回顺序。
- 成功 envelope 的上游列表为空是 `NoData`。任一行字段缺失、不可解析/非有限/非正价格或 OHLC 关系矛盾时整批为 `Protocol`，不得过滤坏行后部分成功。
- 完全相同的重复时间戳行可折叠为一行；同时间戳但任一字段不同则整批为 `Protocol`，不得任意保留第一行或最后一行。
- 上游少于 limit 但非空有效；最新未闭合 K 线保留且不得伪装为已闭合。
- 成功必须同时满足 HTTP 2xx 和厂商 envelope：Binance 顶层数组、Bybit `retCode == 0`、Bitget `code == "00000"`。
- 请求不得添加认证头、Cookie 或设备网络标识。

### 4.1 超时、限频和错误

- OkHttp：connect 10 秒、read 15 秒、call 20 秒。
- 同一 host 的快速手动请求起始时间至少间隔 250ms。
- 连接失败、读取超时、HTTP 408/429/5xx 最多自动重试 1 次；指数退避基数 1 秒并加抖动。优先遵循 `Retry-After`/厂商 reset；无提示的 429 至少等 5 秒。
- Binance 418、Bybit 频率型 403、普通 4xx、参数/币对错误、协议错误和 `NoData` 不自动重试。Bybit 频率型 403 至少停止该 host 10 分钟。
- 稳定错误类型：`Cancelled`、`Connectivity`、`Timeout`、`RateLimited(retryAt?)`、`RequestRejected`、`AccessRestricted`、`UpstreamUnavailable`、`Protocol`、`NoData`。
- 所有失败均保留最后成功图和缓存，不用失败时间覆盖数据时间。
- 产品 Repository 的 Query/缓存/图表链路始终执行 limit `120..1000`。验收矩阵中的 `limit=3` 仅是 App 外部发布工具可选的端点探针，不得进入生产 Repository、UI、Room 或 WebView，也不能替代 limit=120 的六源 App 端到端 smoke。
- 默认用户文案分别为“网络连接失败”“请求超时”“请求过于频繁”“交易对或参数不受支持”“当前网络或地区无法访问该数据源”“数据源暂时不可用”“行情数据格式异常”“没有可用行情”。气泡前缀仍须带 requestedQuery；`retryAt` 存在时显示可重试时间。
- DataStore/Room 失败归入原生 `LocalPersistence`，WebView 握手/渲染进程/JS 错误归入 `ChartRuntime`；二者不是网络九类错误，分别显示“本地数据读取失败”和“图表加载失败”，并提供安全重试/重建路径。

## 5. 图表与算法兼容契约

实现必须以 [现有图表行为与算法契约](../.issues/wayfinder/android-app/assets/现有图表行为与算法契约.md) 为算法真值；以下为不可更改的核心语义：

- K 线时间传 JS 前用 `floor(openTimeMs / 1000)` 得 UTC Unix 秒；时间显示固定 `Asia/Shanghai` / `zh-CN`。
- OHLC 转 JS `Number` 后不对中间值舍入；价格显示精度由原始字符串最多小数位推断，范围 0..12。
- Z-score 使用包含当前值的总体标准差；窗口不足、含非有限值或标准差为零时返回 0。
- 极端阈值使用严格不等号；WMA 最新值权重最高，分母 `length * (length + 1) / 2`。
- `close < WMA` 才是熊市，相等不是；熊/非熊交界按 `close - WMA` 的线性零交叉分割填充。
- 抄底标记固定使用 recentExtremeLookback=12、breakoutLookback=5、markerCooldown=8，并保持确认优先、严格比较和冷却语义。
- 上涨 K 线 `#00b050`，下跌 `#ff3b30`，背景 `#101216`，网格 `#20252d`；WMA 2px 白线，熊市填充 `rgba(0,128,0,0.30)`。
- 应用新币对/周期/source/limit 后 `fitContent()`；只改算法保持 viewport；旋转不 `fitContent()`。
- Lightweight Charts 首版固定内置现有页面使用的 `4.2.3` standalone production 文件，并记录资源 SHA-256；升级库版本需单独回归算法、图形、手势和 viewport，不随依赖例行升级。

回归必须提交静态 `jt-regime-v1.json` 和 `wma-v1.json` 夹具。数值误差使用绝对或相对 `1e-10` 中较宽者；极端布尔、标记数量/类型/顺序/时间/颜色和所属 K 线必须精确一致。

最低硬断言包括：指标首点为索引 3 / `time=1700010800` / `value=0`；索引 39 的 `value=-1.265519052690`；索引 40 的 `z1=-2.999999990384`、`isExtremeDown=true`、`value=0.065927102399`；索引 47 的 `value=0.061237950808`；三枚固定标记完全一致。WMA `[10,11,9,8,12]` / 周期 3 的结果依次为 `59/6, 53/6, 61/6`，bear 为 `[true,true,false]`。

## 6. WebView 边界与安全

### 6.1 本地资源与握手

- HTML/CSS/JavaScript 和锁定版本的 Lightweight Charts 全部打包进 APK。
- 使用 `WebViewAssetLoader` 加载唯一顶层页 `https://appassets.androidplatform.net/assets/chart/index.html`，禁止 `file://`。
- Kotlin/JS 使用 AndroidX WebKit `WebMessageChannel`。原生创建 channel 后，只通过 `WebViewCompat.postWebMessage` 向精确 target origin `https://appassets.androidplatform.net` 发送 bootstrap 消息并转移一个 port；禁止 `*` target origin。接收 bootstrap 的页面必须同时核对固定 origin、协议版本和 pageInstanceId。页面导航白名单与 CSP 构成第二层约束。不用通用 `addJavascriptInterface`，不用字符串拼接 `evaluateJavascript` 传整批行情。
- Kotlin 创建通道并发 `HELLO`；JS 在两图、series 和订阅就绪后回 `READY`。`READY` 前只保留最新快照。
- `HELLO` 后 5 秒未收到匹配的 `READY` 时可重载本地页 1 次，仍失败进入原生 `ChartRuntime` 错误。

### 6.2 消息和快照

固定 envelope：

```json
{"v":1,"type":"chart.renderSnapshot","id":"uuid","pageInstanceId":"uuid","generation":42,"renderRevision":7,"payload":{}}
```

- `generation` 是网络请求世代；`renderRevision` 是同一 generation 内单调递增的绘图世代。算法参数本地重绘只增加 renderRevision，不取消同 Query 的在途网络请求；网络成功产生新快照时再增加 renderRevision。
- `v` 不匹配时 fail closed；响应复制 `id/pageInstanceId/generation/renderRevision`；旧页面、旧 request generation 或旧 renderRevision 回执丢弃。
- v1 wire type 固定为：Kotlin -> JS 的 `chart.hello`、`chart.renderSnapshot`、`chart.setInteractionEnabled`、`chart.requestViewport`、`chart.dispose`；JS -> Kotlin 的 `chart.ready`、`chart.renderAck`、`chart.viewportChanged`、`chart.viewportCaptured`、`chart.error`。代码内枚举名可不同，线上字符串不得不同。
- `chart.error.payload.stage` 只允许 `decode|validate|algorithm|render|restore`；v1 稳定 code 分别只允许：decode=`malformed_json`；validate=`unsupported_version|invalid_envelope|invalid_snapshot|payload_too_large`；algorithm=`algorithm_failed|non_finite_output`；render=`chart_init_failed|chart_update_failed`；restore=`invalid_viewport|restore_failed`。原始异常/stack 仅 debug 脱敏诊断，不作为用户文案。
- 每个 generation 以一条紧凑 JSON 原子传输最多 1000 根 K 线；每根使用 `[openTimeMs,"open","high","low","close"]`。
- 单个 DecimalString 最长 128 个 ASCII 字符；完整 UTF-8 消息最大 2 MiB。越界整批拒绝为协议错误，不静默截断。
- `viewPolicy` 只允许 `fitContent`、`restoreExact`、`preserveOrFollowRight`。持久化恢复用前两者；同 Query 网络刷新用第三种。
- 不分片、不压缩、不 Base64、不逐根调用。整个快照解析/校验/计算成功后才原子换图，失败保留已绘制图。

### 6.3 生命周期与安全配置

- HTTP/解析/持久化 I/O 在 `Dispatchers.IO`；大 JSON 序列化在 `Dispatchers.Default`；WebView 操作在 Main。
- 旋转销毁旧 WebView，新实例使用新 `pageInstanceId`，`READY` 后重发 ViewModel 快照；旋转本身不联网。
- CSP 默认拒绝，`connect-src 'none'`；仅允许所需本地脚本/样式和必要 `data:` 图片。
- `allowFileAccess=false`、`allowContentAccess=false`、mixed content `NEVER`、DOM storage 关闭、第三方 Cookie 关闭。
- 拒绝非唯一 appassets URL 的导航、新窗口、下载、外链和权限请求。
- WebView 调试仅 debug 允许，release 必须关闭；每次销毁页面时关闭旧 message port。

## 7. 缓存、viewport 与刷新状态机

### 7.0 身份定义

- `Query = {source, symbol, interval, limit}`；`QueryKey` 是这四个已规范化字段的结构化值，不使用易碰撞的裸字符串拼接。
- `DatasetFingerprint` 为规范化完整 CandleSnapshot 的 SHA-256 小写十六进制。哈希输入固定为 UTF-8、无空白 JSON：`{"v":1,"query":{"source":...,"symbol":...,"interval":...,"limit":...},"candles":[[openTimeMs,"open","high","low","close",baseVolumeOrNull,quoteVolumeOrNull],...]}`。字段顺序固定，DecimalString 保持原文。
- `displayedDataset = {queryKey, datasetFingerprint, fetchedAtEpochMs, candles}`；`requestedQuery` 可以与它不同。
- bridge 和持久化统一使用 `queryKey`/`datasetFingerprint`，不再引入含义重叠的 `datasetKey`。

### 7.1 Room 缓存

- 缓存键严格为 `source + symbol + interval + limit`；算法参数不入键。
- 每键保留一份最新成功快照：queryKey、`fetchedAtEpochMs`、`lastAccessedAtEpochMs`、`datasetFingerprint`、原始 Candle 数组和 schema/protocol version。
- v1 在 Room 中将每份 CandleSnapshot 存为 UTF-8 紧凑 canonical JSON BLOB；32 MiB 按所有 snapshot BLOB 的字节长度之和计算，不按 SQLite 文件页大小计算。LRU 时间相同时以 canonical QueryKey 字典序作为确定性淘汰 tie-break。
- 仅网络成功且规范化全部通过后，在单个 transaction 中替换；取消或任何失败不写入。
- 上限为 64 个快照且 payload 合计不超过 32 MiB；任一超限按 `lastAccessedAt` LRU 清理，当前展示键和正在写入键不可清理。
- Room 损坏时可重建行情缓存，但不得删除 DataStore 设置；记录脱敏本地诊断。
- Room/DataStore schema 从 v1 开始。DataStore 必须显式迁移或回落默认值；Room 只允许对可再下载的行情/viewport 缓存做有诊断记录的 destructive rebuild，不得连带删除设置。

### 7.2 Viewport

- 保存 `{queryKey,datasetFingerprint,logicalFrom,logicalTo,updatedAt}`；不保存像素坐标、十字光标或完整 K 线。
- ViewModel 保留当前 viewport 供旋转；Room 按快照保留最后 viewport 供进程重建。
- JS 回报以 500ms debounce 持久化，页面停止前尽力 flush。
- 旋转或进程恢复时，仅 queryKey + fingerprint 完全匹配才用 `restoreExact`，越界范围夹到数据域；否则 `fitContent()`。同 Query 会话内网络刷新即使 fingerprint 更新，也按实时 viewPolicy 处理，不误套持久化恢复规则。

### 7.3 刷新与生命周期

- 自动刷新使用一次性 timer；每次成功或失败后从完成时刻重新计时，不用 fixed-rate。
- 只有前台、未锁屏、图表可见、WebView `READY`、无活跃请求且自动刷新开启时才调度。
- 进入后台、锁屏或图表不可见时取消 timer 和活跃请求，不使用 Worker/Alarm。
- 回到前台且图表可见时只立即刷新 1 次，不补跑错过周期；若 WebView 正重建，先恢复快照再刷新。
- 同 Query 网络刷新时，若刷新前 logical range 的右端距离旧数据最后 logical index 不超过 2 根，则视为位于最新边缘并跟随新末端；否则保持原 logical range。该规则同时适用于自动刷新、立即刷新和回前台刷新。
- 旋转不请求、不改缓存时间、不重置自动刷新 timer，保留设置、草稿、requested/displayed、图表、气泡和 viewport。
- 进程重建恢复已应用设置、同键缓存和匹配 viewport，但不恢复未应用草稿。
- 旋转期间暂停触发但保留原自动刷新 deadline；新 WebView `READY` 时若 deadline 已过，只立即刷新 1 次，否则按剩余时长继续，不丢失或叠加 timer。

### 7.4 应用设置的精确副作用

- symbol/source/interval/limit 改变：原子提交新 Query，generation +1，先查同键缓存并发起网络刷新；`fitContent()` 时机按本节末尾的缓存命中规则执行。
- 只改变算法参数：不联网、不改变 QueryKey/request generation；renderRevision +1，用当前 CandleSnapshot 本地重算/重绘并保持 viewport。
- 只改变自动刷新开关/间隔：不联网、不 `fitContent()`；只取消并按新配置重排一次性 timer。
- “立即刷新”：同 Query 发起网络刷新，按“距右端不超过 2 根”规则保持历史视野或跟随最新。
- UI 按钮可保留“应用并刷新”，其含义是应用后按字段差异更新图表状态，不代表所有设置变化都强制联网。
- 同一次应用若跨多类字段，原子持久化全部已应用设置并组合执行全部副作用：Query 字段变化负责新 request generation/缓存/联网，算法字段随当前缓存或新响应重绘，刷新字段同时重排 timer，不能互斥地只走一个分支。
- 新 Query 命中同键缓存时，切换到缓存后立即 `fitContent()` 1 次；随后网络成功属于同 Query 刷新，使用 `preserveOrFollowRight`，不再次无条件 `fitContent()`。无同键缓存时保留旧图，待新 Query 首次成功后 `fitContent()`。

## 8. 工程与构建约束

- 工程目录：`android-app/`；单 App 模块即可，业务边界通过 package/interface 隔离，首版无需为形式拆多 Gradle module。
- UI：Kotlin、Jetpack Compose、Material 3；状态通过 ViewModel + coroutine/Flow 管理。
- 持久化：Proto DataStore + Room；网络：OkHttp；WebView 通道：AndroidX WebKit。
- 使用项目 Gradle Wrapper 和版本目录锁定全部插件/依赖，不依赖系统 Gradle，也不使用动态版本。
- 构建基线使用 Android Studio SDK `~/Library/Android/sdk`、Studio JBR 21.0.10、compileSdk/targetSdk 36 和 Build Tools 36.1.0；minSdk 固定 29。Pixel 验收设备为 API 36。
- 当前规划没有伪造尚未验证的 AGP/Gradle Wrapper/Kotlin/Compose/WebKit/Room/DataStore/OkHttp 精确版本。工程 bootstrap 必须先从官方兼容矩阵选一组支持 JBR 21 与 SDK 36 的稳定组合，执行最小 debug 构建与 lint 后，将精确版本提交到 wrapper/version catalog；该机械兼容选择不得改变本规格行为。已验证“可启动”的本机 Gradle 8.7 不自动等于项目 Wrapper 版本。
- 受限会话将 `GRADLE_USER_HOME` 指向 `android-app/.gradle-user-home` 并 gitignore。
- release 构建缺任一签名 property 必须 fail fast，不能回退 debug 签名或产生伪 release。
- Manifest 只声明联网所需的 `INTERNET`；禁止 cleartext（`usesCleartextTraffic=false`），组件默认不导出，只有 launcher Activity 按 Android 要求导出。`allowBackup=false`，不把设置或行情交给系统/云备份。
- OkHttp 自身 HTTP response cache 关闭，避免与 Room 的产品缓存形成第二套新鲜度语义；不内置代理、证书绕过或用户可编辑 host。
- release 启用 R8 minify 与 resource shrink，并为序列化/Room/WebKit 添加最小 keep 规则；release APK 必须确认 test-only fixture server、故障注入器、调试菜单和 WebView 调试入口均被裁掉。
- `onRenderProcessGone` 时销毁旧 WebView/port，保留 ViewModel 的 displayedDataset，使用新 pageInstanceId 自动重建 1 次；若尚未完成一次匹配的 `READY + RENDER_ACK` 又再次崩溃，则进入 `ChartRuntime`。完成稳定 ACK 后才重置一次恢复额度，禁止无界重载循环。
- 所有可操作 Compose 控件提供中文 `contentDescription`，关键抽屉分页、应用/取消、重试、标题、气泡和图表容器设置稳定 testTag；系统大字体下不得遮挡应用/取消和错误重试入口。

## 9. 签名、版本与分发

- 所有 release 使用仓库外唯一 app signing key；首发前建立证书 SHA-256 基线。
- 建议 keystore `~/.config/jt-chart/signing/jt-chart-release.jks`、alias `jt-chart-release`；私有目录 0700、文件 0600。
- Gradle 只读取 `JT_CHART_STORE_FILE`、`JT_CHART_STORE_PASSWORD`、`JT_CHART_KEY_ALIAS`、`JT_CHART_KEY_PASSWORD`，由仓库外 Gradle properties 或受控 `ORG_GRADLE_PROJECT_*` 环境变量提供。
- keystore、密码、`.env` 和私钥不得进入仓库、日志、构建输出或分发包；keystore 至少两份异地加密备份，并在首发前做恢复签名演练。
- 首版 `versionName=0.1.0`、`versionCode=1`；以后每个对外分发版严格递增 versionCode，已用编号不复用。
- 文件名 `JT-Chart-v{versionName}-c{versionCode}-release.apk`。
- 分发包只含 APK、`SHA256SUMS.txt`、`RELEASE_NOTES.md`。
- 发布前使用 `apksigner`、`apkanalyzer` 和 SHA-256 校验签名、证书、applicationId、版本、min/target SDK 和 `debuggable=false`。
- 首版在 Pixel 与小米做全新安装并留存基线 APK；第二版起必须从上一已发布 release 用 `adb install -r` 做保留设置/缓存/viewport 的真实覆盖升级，不允许先卸载或清数据。
- Pixel 和小米最终门禁必须使用同一 SHA-256 的候选 APK；真机验收前不得重构建。

## 10. 实施顺序

1. 建立可重复的 Gradle 工程、版本锁、debug 构建、CI 和 release 签名 fail-fast 骨架。
2. 提交静态算法/六源/错误/桥接/缓存 fixture，先完成 Repository、算法兼容和持久化测试。
3. 实现 ViewModel 状态机、generation 请求协调器、缓存先显和顶部失败气泡。
4. 迁移本地图表资源，实现 WebViewAssetLoader、WebMessageChannel、握手、快照和安全配置。
5. 实现 Compose 图表页与 B 方案设置抽屉、手势边界、旋转和 viewport 恢复。
6. 跑无公网 CI，再在 Pixel 虚拟机跑 fixture 故障、UI、生命周期、安全和六源公网 smoke。
7. 配置仓库外 release 签名，完成产物校验和覆盖升级门禁。
8. 小米 17 Pro Max 在线后，用同一 SHA-256 release APK 完成 HyperOS 最终门禁；通过前不得对外交付。

## 11. 验收与完成定义

详细测试 ID、机械命令和证据格式以 [端到端验收矩阵](../.issues/wayfinder/android-app/assets/端到端验收矩阵.md) 为准。完成必须同时满足：

### 11.1 普通 CI

- 无公网、无签名秘密；`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全绿。
- 固定夹具覆盖算法、六适配器/42 个周期映射、九类错误与重试、generation 竞态、Room/DataStore、消息协议和 WebView 安全。
- release 缺签名 property 的负测试 fail fast。

### 11.2 Pixel 9 Pro XL API 36

- ADB 基线、构建、安装、启动、Logcat、截图和 instrumented/UI 测试通过。
- 设置草稿、抽屉手势、图表金图、range 联动、坏快照、旧 generation/page instance、安全负测试通过。
- 冷启动缓存、7 天以上缓存、失败旧图/置顶气泡、快速切换、断网恢复、旋转、后台/锁屏、进程重建通过。
- 六源公网 smoke 全通；地区限制不能通过代理或自建后端规避。
- 签名 release 的验签、manifest、SHA-256、secret 扫描和适用的覆盖升级通过。
- 此阶段最多标记为“开发候选版”。

### 11.3 小米 17 Pro Max / HyperOS

- 使用与 Pixel 门禁相同 SHA-256 的签名 release APK。
- 完成安装/覆盖升级、安全区、系统返回与抽屉手势、系统 WebView、Wi-Fi 六源、断网恢复、生命周期、旋转和 30 分钟稳定性测试。
- 真机未在线或任一必过项失败时，最终对外交付保持阻塞；移动网络因无 SIM 可有理由 skip，Wi-Fi 六源不可跳过。

### 11.4 无后端证明

- 源码和 release APK 只包含四个官方上游 host 及 appassets 虚拟 origin，不含旧服务 API、自建地址、CDN 或 test-only 可编辑 base URL。
- WebView CSP 为 `connect-src 'none'` 且联网负测试通过。
- 不启动 Docker、`chart_server.py` 或工作区 HTTP server 时，设备上的生产 Repository 仍可完成六源请求和绘图。

## 12. 证据与状态声明

- 普通 CI、Pixel、release 和真机门禁均按 run/device 保存 JUnit/Lint、命令退出码、脱敏 Logcat、截图/录像、六源摘要、验签/manifest/哈希和 `manifest.json`。
- 内部证据放在 gitignored 的 `android-app/qa-evidence/` 或永久制品库；公共 `android-app/dist/<release>/` 严格只含 APK、`SHA256SUMS.txt`、`RELEASE_NOTES.md`，避免误把证据装入分发包。
- 当前规格完成时 `android-app/` 只有本文档，尚无 Android 工程、APK 或 App 测试结果；已验证的仅是 Pixel AVD、SDK/JBR、ADB、Logcat 和截图工具链基线。任何实施会话不得把本规格中的未来门禁描述成已经通过。

## 13. 详细契约索引

- [现有图表行为与算法契约](../.issues/wayfinder/android-app/assets/现有图表行为与算法契约.md)
- [六数据源直连契约](../.issues/wayfinder/android-app/assets/六数据源直连契约.md)
- [Android 工具链与 Pixel 虚拟机基线](../.issues/wayfinder/android-app/assets/Android工具链与Pixel虚拟机基线.md)
- [移动端抽屉原型与 B 方案](../.issues/wayfinder/android-app/assets/mobile-drawer-prototype/README.md)
- [原生外壳与 WebView 边界契约](../.issues/wayfinder/android-app/assets/原生外壳与WebView边界契约.md)
- [缓存、前台刷新与状态恢复契约](../.issues/wayfinder/android-app/assets/缓存前台刷新与状态恢复契约.md)
- [APK 签名、分发与升级契约](../.issues/wayfinder/android-app/assets/APK签名分发与升级契约.md)
- [端到端验收矩阵](../.issues/wayfinder/android-app/assets/端到端验收矩阵.md)
