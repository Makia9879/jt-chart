---
id: wf-android-app-0005
title: 定义原生外壳与 WebView 边界
parent: wayfinder-jt-chart-android-app
labels:
  - wayfinder:grilling
status: closed
assignee: /root
blocked_by:
  - wf-android-app-0001
  - wf-android-app-0002
  - wf-android-app-0004
---

## Question

原生 Compose 层、行情 Repository、本地持久化层与 WebView JavaScript 图表之间的职责、数据流、消息协议、线程边界、导航限制和 JS bridge 安全边界应如何定义？需明确旋转恢复、页面就绪握手、大批量 K 线传输、错误回传与本地 asset 来源约束，使实现时不再需要临时决定架构分工。

## Resolution

用户确认采用 A：`WebViewAssetLoader + WebMessageChannel`。完整决策见[原生外壳与 WebView 边界契约](../assets/原生外壳与WebView边界契约.md)。

关键结论：

- Kotlin Repository 仍独立实时请求六数据源；通道只负责把已获得的规范化行情交给图表，不降低刷新实时性。
- Compose/ViewModel 是 UI 状态唯一所有者；Repository 负责网络；Kotlin 持久化层独占设置/缓存；JavaScript 只计算和绘图。
- 使用版本化 envelope、`pageInstanceId` 和 generation 防止页面/请求竞态；等 JS `READY` 后再发最新快照。
- 最多 1000 根 K 线以单条紧凑 JSON 原子传输，十进制字符串跨桥不丢失；不分片、不压缩、不逐根调用。
- 旋转重建 WebView 但不联网；重发 ViewModel 快照，仅当 dataset fingerprint 一致时恢复 logical range。
- 所有图表资源内置 APK，WebView 禁止网络、远程脚本、Web Storage、非受信导航和 release 调试。
