---
id: wayfinder-jt-chart-android-app
title: 纯 Android 图表 App 执行路线图
labels:
  - wayfinder:map
status: closed
---

## Destination

形成一份可直接交给后续实现会话执行的 Android MVP 规格：`JT Chart` 以签名 APK 分发，在 Pixel 9 Pro XL 虚拟机上独立请求六种交易所公共行情、计算并展示现有图表，无自建后端、无通知模块。真机在线后，再以小米 17 Pro Max 完成最终发布关卡。

## Notes

- 本地 Markdown tracker 根目录为 `.issues/wayfinder/android-app/`；本地 tracker 使用票据 frontmatter 中的 `parent` 和 `blocked_by` 表达子票据与依赖。
- Wayfinder 阶段只解决规格和技术决策，不实施 App；决策已汇总为 [JT Chart Android MVP 规格](../../../android-app/MVP_SPEC.md)，实现仍需另行授权。
- 首版是忠实迁移：保留币种切换与排序、周期、K 线数量、数据源、自动刷新、JT Regime、抄底标记、熊市 WMA 与参数持久化。
- UI 采用 Kotlin + Jetpack Compose 原生外壳和 APK 内置 WebView 图表；左上角为设置入口，设置以 Material 3 左侧抽屉展示，抽屉关闭后不占图表空间。
- Kotlin 网络层直连 Binance Spot、Binance USDT-M、Bybit Spot、Bybit USDT Perp、Bitget Spot 和 Bitget USDT Perp，统一行情数据后交给 WebView；不在 WebView 中直连交易所。
- Lightweight Charts 及页面资源必须打包进 APK；图表计算与绘制继续复用 JavaScript，不在首版重写为原生 Canvas。
- 设置与最后成功行情需本地持久化；打开时先显示缓存，再刷新并标识数据新鲜度。
- 自动刷新只在前台且图表可见时运行；进入后台或锁屏后暂停，回到前台立即刷新一次。
- 支持手机横竖屏；旋转时保留币种、设置、已加载数据及可行的图表视图状态。首版不做平板或折叠屏专属布局。
- App 名为 `JT Chart`，`applicationId` 为 `com.makia.jtchart`，工程目录为 `android-app/`，首版版本为 `0.1.0`，最低 Android 10（API 29）。
- 开发使用 debug 签名；分发交付使用可持续覆盖升级的签名 release APK。Keystore 和密码不进 Git。
- 验收由 agent 执行：先在 Pixel 9 Pro XL 虚拟机完成；小米 17 Pro Max 当前不在线，不阻塞虚拟机开发，但真机验证仍是最终发布关卡。
- 每次处理子票据前先根据类型使用对应 skill；一次 Wayfinder 会话最多关闭一张票据。

## Decisions so far

<!-- 关闭子票据后，在此追加一行摘要与链接。 -->

- [固化现有图表行为与算法契约](tickets/0001-固化现有图表行为与算法契约.md) — 保留 JavaScript binary64 算法语义、WMA/标记/双图 logical range 行为，以固定 K 线夹具和 `1e-10` 容差验收。
- [定义六数据源直连契约](tickets/0002-定义六数据源直连契约.md) — 六种公共 K 线源统一为按开盘时间升序的高精度行情，并固化超时、有限重试、限频和九类错误契约。
- [建立 Android 工具链与 Pixel 虚拟机基线](tickets/0003-建立Android工具链与Pixel虚拟机基线.md) — 统一使用 Studio SDK/JBR 21 与可写 Gradle 目录，已实测 Pixel 9 Pro XL API 36 的 ADB、Logcat 和截图闭环。
- [原型验证移动端图表与设置抽屉](tickets/0004-原型验证移动端图表与设置抽屉.md) — 选定 B 分类分页草稿态与统一应用，用左缘 12dp 和抽屉遮罩明确分隔 Compose 与图表手势。
- [定义原生外壳与 WebView 边界](tickets/0005-定义原生外壳与WebView边界.md) — 采用 WebViewAssetLoader + WebMessageChannel，Kotlin 独占网络/状态/持久化，JS 只算法绘图，通过版本化快照协议安全恢复。
- [定义缓存、前台刷新与状态恢复](tickets/0006-定义缓存前台刷新与状态恢复.md) — 以精确查询键持久化最后成功数据，失败保留旧图并置顶标明身份，刷新严格受 generation 和前台可见性约束。
- [定义 APK 签名、分发与升级契约](tickets/0007-定义APK签名分发与升级契约.md) — 使用仓库外唯一密钥签名单调递增版本 APK，发布前必须验签、校验 manifest/SHA-256 并实测保留数据的覆盖升级。
- [定义端到端验收矩阵](tickets/0008-定义端到端验收矩阵.md) — 分层固化无公网 CI、Pixel 虚拟机候选门禁和 HyperOS 真机最终关卡，每项都保留可哈希证据。

## Not yet specified

- 无；MVP 规格所需决策已收敛。真机实施中发现的问题按发布缺陷另行开票。

## Resolution

八张子票据已全部关闭，算法、六源直连、Android 环境、移动端交互 B 方案、原生/WebView 边界、缓存与生命周期、签名分发和三层验收门禁已汇总为 [JT Chart Android MVP 规格](../../../android-app/MVP_SPEC.md)。

本路线图的完成只证明“实施路径已明确”，不证明 Android 工程、APK、Pixel App 验收或小米真机验收已经完成。进入实现仍需用户明确授权；Pixel 9 Pro XL 是开发候选版门禁，小米 17 Pro Max / HyperOS 是最终对外交付硬门禁。

## Out of scope

- 企业微信、ElectricWave、系统通知、后台监控 Worker 与任何后台定时任务。
- 自建后端、Docker 运行时依赖、账户系统、云同步和远程配置。
- 交易所私有 API、API Key、账户余额、下单或自动交易。
- 应用商店、AAB、商店签名、付费或公开发布流程。
- 将 Lightweight Charts 或策略图表全面重写为原生 Compose/Canvas 图表。
- 平板、折叠屏、桌面模式的专属交互设计。
