# 原生外壳与 WebView 边界契约

> 适用于 `JT Chart` Android MVP。用户已确认采用方案 A：`WebViewAssetLoader + WebMessageChannel`。

## 核心边界

行情获取与图表绘制是两条独立职责：

```text
交易所公开 API
  → Kotlin Repository（请求、规范化、取消、重试、错误分类）
  → ViewModel（唯一 UI 状态所有者）
  → WebMessageChannel（本地受信通道）
  → WebView JavaScript（算法与图表绘制）
```

WebView 通道不改变 Repository 的实时请求、超时、重试或刷新频率。WebView 不能直接请求 Binance、Bybit 或 Bitget。

## 职责分工

### Compose 与 ViewModel

- Compose 负责 Material 3 抽屉、方案 B 的分页草稿/取消/统一应用、币种管理、标题、加载/离线/错误/新鲜度表示和抽屉遮罩。
- ViewModel 是已应用设置、抽屉草稿、当前查询、请求 generation、K 线快照、绘制摘要和 viewport 快照的唯一所有者。
- 应用新设置、换币种/周期/数据源时取消旧请求并递增 generation；旧 generation 的网络结果和图表回执一律丢弃。
- Repository 错误直接进入原生 UI 状态，不经过 JavaScript 翻译。

### 行情 Repository

- 只负责六数据源的 URL/周期映射、HTTP 请求、超时、有限重试、取消、排序去重、十进制字符串校验和稳定错误分类。
- 输入为 `Query(symbol, source, interval, limit)`，输出为规范化且按时间升序的 `Candle` 列表。
- OHLC 在 Repository、缓存和跨桥前保持原始十进制字符串，不提前转 `Float`/`Double`。
- Repository 不依赖 Compose 或 WebView，不计算 JT Regime/WMA，不直接操作 UI。

### Kotlin 持久化层

- Kotlin 独占所有设置、币种顺序、最后成功 K 线及其 query key/更新时间、可恢复 viewport 的持久化权。
- JavaScript 不使用 `localStorage`/`sessionStorage`。
- 具体存储介质、key、容量、清理和新鲜度规则由“定义缓存、前台刷新与状态恢复”决定。

### WebView JavaScript

- 只负责从 DecimalString 推断价格精度并转 JavaScript `Number`、JT Regime/WMA/标记计算、Lightweight Charts 绘制、熊市 canvas 叠加、两图 logical range 同步和 viewport 回报/恢复。
- 不拥有设置、自动刷新 timer、网络请求、持久化、厂商错误分类或原生错误文案。
- 现有 HTML 只迁移算法、图表、标记、叠加层、格式化和 range 同步；删除 CDN、`fetch`、设置 DOM、通知/worker、timer 和 Web Storage。

## 本地页面与通道

- 图表 HTML/CSS/JavaScript 和锁定版本的 Lightweight Charts 全部打包进 APK。
- 使用 `WebViewAssetLoader` 从固定地址 `https://appassets.androidplatform.net/assets/chart/index.html` 加载，不使用 `file://`。
- Kotlin 与页面使用 `WebMessageChannel`/AndroidX WebKit 受 origin allowlist 限制的 WebMessage 通道。不使用字符串拼接的 `evaluateJavascript` 传整批行情，不暴露通用 `addJavascriptInterface` 对象。
- `onPageFinished` 不等于图表就绪。Kotlin 创建通道并发送 `HELLO`；JS 在两张图、series 和 range subscription 创建完成后回 `READY`。
- `READY` 之前 Kotlin 只保留最新快照；收到 `READY` 后才发送。握手超时可重载本地页一次，仍失败则进入原生 `ChartRuntime` 错误。

## 版本化消息协议

每条消息使用固定 envelope：

```json
{
  "v": 1,
  "type": "chart.renderSnapshot",
  "id": "uuid",
  "pageInstanceId": "uuid",
  "generation": 42,
  "payload": {}
}
```

- `v`：协议版本，不匹配时 fail closed。
- `pageInstanceId`：每次页面创建都不同，防止旧 WebView 回调污染新页面。
- `generation`：每次查询/重载递增，防止旧数据覆盖新数据。
- 响应复制请求的 `id/pageInstanceId/generation`。未知类型忽略并记录本地诊断。

### Kotlin → JavaScript

- `HELLO`：协议版本和原生能力。
- `RENDER_SNAPSHOT`：`datasetKey` + 算法设置 + 完整 K 线 + `viewPolicy`。
- `SET_INTERACTION_ENABLED`：抽屉打开时 `false`，关闭时 `true`。
- `REQUEST_VIEWPORT`：请求当前 logical range。
- `DISPOSE`：页面销毁前清理订阅和图表资源。

### JavaScript → Kotlin

- `READY`：页面、协议和 Lightweight Charts 版本/能力。
- `RENDER_ACK`：已绘制 generation、K 线数量、最新 Regime、标记数/最近时间和极端状态。
- `VIEWPORT_CHANGED` / `VIEWPORT_CAPTURED`：带 `datasetKey` 的 logical range。
- `CHART_ERROR`：`decode | validate | algorithm | render | restore` 阶段和稳定错误码。原始 stack 仅在 debug 诊断使用，不记录整批行情。

## K 线快照传输

- 六源共同上限为 1000 根，每个 generation 用一条紧凑 JSON 原子传输，不分片、不用 Base64/gzip、不逐根调用 JavaScript。
- Candle 使用紧凑数组：`[openTimeMs, "open", "high", "low", "close"]`。JS 最后一步整除 1000 得到 Unix 秒并转 `Number`。
- 入口校验 `count <= 1000`、时间升序、字段/数值范围、decimal string 长度与总 payload 防御上限。超限或非法归类为协议错误，不静默截断。
- JS 必须在整个快照 parse/校验/计算成功后才原子替换图表；失败保留已绘制图表并回 `CHART_ERROR`。

## 线程和生命周期

- HTTP、解析和持久化 I/O 在 `Dispatchers.IO`；大 JSON 序列化在 `Dispatchers.Default`；WebView 创建、加载、postMessage、导航和销毁在 Main 线程。
- JavaScript 算法与 Lightweight Charts 在 WebView 主执行上下文运行；同时只接受最新 generation。
- 前台且图表可见时才刷新；后台/锁屏/切换 query 时取消请求和 timer。精确时序由后续缓存/刷新契约固化。
- Activity/WebView 不跨配置变更强行保留。旋转时 ViewModel 保留设置、K 线快照、绘制摘要和 `{datasetKey, logicalRange}`；销毁旧 WebView，新实例 `READY` 后重发相同快照。
- 仅当 dataset fingerprint 相同时恢复 logical range；先 `setData`，再恢复 range，旋转不重新联网且不 `fitContent()`。
- 完整 K 线不放入 `Bundle`/`SavedStateHandle`；进程重建后由 Kotlin 缓存恢复。

## WebView 安全约束

- CSP 默认拒绝，仅允许本地脚本/样式和必要的 `data:` 图片；`connect-src 'none'`，禁止 CDN、远程脚本、frame、object 和不受信导航。
- `allowFileAccess=false`、`allowContentAccess=false`、mixed content `NEVER`、DOM storage 关闭、第三方 Cookie 关闭。
- 仅允许固定 appassets 图表 URL；拒绝其他导航、新窗口、下载、权限请求和 WebView 内外链。
- WebView 调试仅 debug build 允许，release build 必须关闭。
- 每次页面导航/重建销毁旧 message port 并重新握手。

## 验收点

- `READY` 前不发送行情，重复 `READY` 幂等；旧 page instance/generation 不更新 UI 或图表。
- 1000 根 K 线、18 位小数和尾随零跨桥不丢失；固定算法夹具在桥前/桥后结果一致。
- 旋转不联网；相同 dataset 恢复 logical range，不同 dataset 不误用旧 range。
- 坏 payload/算法/绘制错误保留旧图，返回稳定错误；整批行情不写日志。
- 图表页在 CSP 下不能 `fetch`、访问 CDN 或导航到外部 URL；release 无 WebView 调试。
- Pixel 9 Pro XL 上用 ADB 安装/启动，通过 Logcat 核对握手、generation 和错误，并用截图验收旋转与手势。
