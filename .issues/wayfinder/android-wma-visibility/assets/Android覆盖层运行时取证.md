# Android 覆盖层运行时取证

## 现象

- 2026-07-14 的 Pixel 9 Pro XL 与红米真机截图均能看到 K 线、JT Regime 和信号标记，但看不到 WMA 白线及绿色熊市填充。
- 2026-07-20 在已安装 JT Chart 0.1.0 的 Android 16 / API 36 真机上再次启动 App，K 线正常且仍看不到 WMA 白线及绿色填充，排除旧截图偶发现象。

## 源码链路

- `algorithms.js` 的 `calculateBearMarketOverlay()` 已按 WMA 周期产生 `{time, close, wma, isBear}`。
- `bridge.js` 已把 `bearOverlay` 放入渲染快照。
- `renderer.js` 的 `drawOverlay()` 已把白线设为 `#ffffff`、`lineWidth = 2`，并绘制 `rgba(0,128,0,0.30)` 填充。
- `index.html` 中 `#bear-overlay` 位于 `#price-chart` 之后，CSS 只设为绝对定位，未显式设置层级。

## 真机 WebView 证据

通过 debug WebView 的 Chrome DevTools Protocol 只读检查当前页面：

- `#bear-overlay` 位图为 `1200 x 1473`，CSS 尺寸约 `400 x 491`，与 3x device pixel ratio 一致。
- 位图中有约 `12,418` 个非透明像素，其中约 `6,430` 个白色像素、`5,989` 个绿色像素，证明算法、快照、坐标转换和 Canvas 绘制都已执行。
- `#bear-overlay` 的 computed `z-index` 为 `auto`。
- Lightweight Charts 命中点上的内部 Canvas computed `z-index` 分别为 `2` 和 `1`。
- 真机截图仍完全看不到覆盖层，因此当前主因是覆盖 Canvas 被 Lightweight Charts 内部图层遮挡，而不是没有 WMA 数据或绘制失败。

## 修复约束

- 应为 `#bear-overlay` 建立明确高于 Lightweight Charts 内部 Canvas 的层级，同时保持 `pointer-events: none`，不能破坏图表手势和十字光标。
- 修复后必须用自动化保护层级契约，并在真机上用当前数据或确定性夹具证明白线可见。
- 若用户选择连同绿色填充验收，同一真机证据还须覆盖熊市区域填充；若用户只选择白线，需明确是否允许现有填充随同恢复可见。
