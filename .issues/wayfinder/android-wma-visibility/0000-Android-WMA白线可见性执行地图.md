---
id: wayfinder-jt-chart-android-wma-visibility
title: Android WMA 白线可见性执行地图
labels:
  - wayfinder:map
status: open
---

## Destination

让 JT Chart Android App 的价格图可靠呈现与 Web 版同语义的熊市 WMA 白线，并以自动化测试和 Android 调试环境中的可见证据证明它在首次加载、缩放平移及横竖屏场景中正确显示。

## Notes

- 本地 Markdown tracker 根目录为 `.issues/wayfinder/android-wma-visibility/`；票据 frontmatter 使用 `parent`、`blocked_by`、`assignee` 表达父子关系、依赖和认领。
- 本地图允许在决策闭合后继续执行交付；用户要求票据列完后立即启用 `aiops-agent-team` 完成全部实现与验证票据。
- Agent Team 必须依次通过范围闭合、分工实现、合并集成、测试部署四个闸门。
- grilling 票据固定由主 Agent `/root` 认领并通过真实用户交互解决，不能由子 Agent 代答。
- 当前代码证据：Android 已包含 WMA 算法、`bearOverlay` 快照和 Canvas 2px 白线绘制；当前缺口需区分为运行时缺陷、可见性/层叠缺陷或仅缺少验收证据。
- 不改变 WMA 数学定义：新值最高权重；`close < WMA` 才属于熊市；默认周期 200；白线 `#ffffff`、2px。

## Decisions so far

<!-- 关闭子票据后，在此追加一行摘要与链接。 -->

- [确定 WMA 白线的产品与验收边界](tickets/0001-确定WMA白线的产品与验收边界.md) — 用户确认白色 WMA 与绿色熊市填充作为同一覆盖层一起恢复、一起自动化和真机验收，不改变既有算法与样式。
- [确认 Android 覆盖层不可见的运行时根因](tickets/0002-确认Android覆盖层不可见的运行时根因.md) — 覆盖 Canvas 已有正确白/绿像素与尺寸，单一根因是其 `z-index:auto` 被 Lightweight Charts 的 `z-index:1/2` 内部 Canvas 遮挡，最小修复是显式层级严格大于 2 且保留 `pointer-events:none`。
- [修复 WMA 覆盖层层叠并补回归保护](tickets/0003-修复WMA覆盖层层叠并补回归保护.md) — 覆盖层已用显式 `z-index:3` 与运行时层叠/指针契约恢复白色 WMA 和绿色填充，并由非空 Canvas 绘制回归及 Android 静态构建保护。
- [合并并完成 Android 全项目静态构建](tickets/0004-合并并完成Android全项目静态构建.md) — 实现提交已无冲突合入主工作树，离线全量 JS、Android JVM、Lint 与 debug APK 构建全部通过，候选 APK 哈希及 Android 16 真机验收约束已固化。

## Not yet specified

- 无；实现范围、根因、修复约束与设备验收范围均已明确。

## Out of scope

- 修改 JT Regime、抄底/逃顶信号的算法语义。
- 全面重写 WebView 图表为原生 Compose/Canvas 图表。
- 新增交易、账户、后台监控或通知能力。
- 与 WMA 白线无关的 Android UI 重设计。

## Execution tickets

- [确定 WMA 白线的产品与验收边界](tickets/0001-确定WMA白线的产品与验收边界.md)（主 Agent grilling，已认领）
- [确认 Android 覆盖层不可见的运行时根因](tickets/0002-确认Android覆盖层不可见的运行时根因.md)（Agent Team 范围研究）
- [修复 WMA 覆盖层层叠并补回归保护](tickets/0003-修复WMA覆盖层层叠并补回归保护.md)（Agent Team 实现）
- [合并并完成 Android 全项目静态构建](tickets/0004-合并并完成Android全项目静态构建.md)（Agent Team 集成）
- [在 Android 16 真机部署并验收 WMA 覆盖层](tickets/0005-在Android16真机部署并验收WMA覆盖层.md)（Agent Team 测试部署）
