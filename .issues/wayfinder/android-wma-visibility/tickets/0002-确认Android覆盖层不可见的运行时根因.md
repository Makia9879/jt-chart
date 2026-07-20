---
id: wf-android-wma-visibility-0002
title: 确认 Android 覆盖层不可见的运行时根因
parent: wayfinder-jt-chart-android-wma-visibility
labels:
  - wayfinder:research
status: closed
assignee: /root/wma_root_cause
blocked_by: []
---

## Question

Android 真机上 WMA 白线与绿色熊市填充不可见，究竟是算法无输出、快照漏传、首次绘制时序、Canvas 尺寸错误还是 CSS 层叠遮挡？需复核[Android 覆盖层运行时取证](../assets/Android覆盖层运行时取证.md)，给出可被实现票据直接采用的单一根因和修复约束。

## Resolution

### 单一根因

`#bear-overlay` 已经成功绘制，但被 Lightweight Charts 的内部 Canvas 层遮挡。覆盖 Canvas 虽然在 DOM 中位于 `#price-chart` 之后，但其 computed `z-index` 是 `auto`；Lightweight Charts 4.2.3 在同一定位上下文中为内部 Canvas 设置了 `z-index: 1` 和 `z-index: 2`。正 `z-index` 内部层因此在 `z-index:auto` 的后置兄弟覆盖层之上绘制。DOM 先后顺序本身不能克服这个层级。

### 证据链

- 两组现有 QA 截图（Pixel 9 Pro XL / Android 16 与 Redmi K40 Gaming / Android 13）均能看到 K 线、信号标记和 JT Regime，但看不到 WMA 白线及绿色填充，说明现象跨设备、横竖屏存在，不是单次截图偶发。
- [Android 覆盖层运行时取证](../assets/Android覆盖层运行时取证.md)记录的 Android 16 真机 CDP 检查显示：覆盖位图为 `1200 x 1473`，与约 `400 x 491` CSS 像素和 3x DPR 一致；位图含约 `12,418` 个非透明像素，其中约 `6,430` 个白色像素、`5,989` 个绿色像素。这同时排除算法无输出、快照漏传、坐标/尺寸错误与未执行绘制。
- 同一次 CDP 检查得到 `#bear-overlay` 的 computed `z-index: auto`，而命中点的 Lightweight Charts 内部 Canvas 为 `1` 和 `2`；在覆盖位图确实有效像素的同时，最终真机截图完全不可见，只与“被上层不透明图表 Canvas 覆盖”一致。
- 源码交叉检查确认：`algorithms.js` 产生 `bearOverlay`，`bridge.js` 将其交给渲染快照，`renderer.js` 在每次快照、可见区间变化、交互与尺寸变化时请求重绘，并实际以 `#ffffff` / 2px 和 `rgba(0, 128, 0, 0.30)` 绘制。
- 只读回归命令 `node --test app/src/test/js/algorithms.test.js app/src/test/js/bridge.test.js app/src/test/js/renderer.test.js` 通过 `13/13`，确认现有算法、快照与渲染调度链路没有提供相反证据；现有 renderer 单测尚未覆盖非空 Canvas 绘制和 CSS 层叠契约，这是测试缺口，不是另一根因。

### 实现票据必须遵守的最小修复约束

1. 只需在现有 `#bear-overlay` 定位模型内建立显式层级；当前 Lightweight Charts 4.2.3 的相关内部 Canvas 最高为 `2`，因此覆盖层 computed `z-index` 必须是严格大于 `2` 的有限整数（最小可用值为 `3`）。不要通过调整 DOM 顺序或修改第三方压缩库解决。
2. 必须保留 `pointer-events: none`，不得改变 Lightweight Charts 接收拖动、缩放、点击、十字光标事件的命中路径。
3. 不得修改 WMA 数学定义、`close < WMA` 熊市判定、默认周期 200、白线 `#ffffff` / 2px、绿色填充色值，也不得绕过当前 `bearOverlay` 快照与重绘链路。
4. 回归保护必须同时证明：非空 `bearOverlay` 会执行绿色 `fill` 和白色 2px `stroke`；覆盖层 computed 层级高于 Lightweight Charts 内部图表 Canvas 且 `pointer-events` 仍为 `none`。仅检查 CSS 文本存在不足以代替运行时 computed-style 契约。
5. 真机验收需用包含至少 200 根 K 线且能产生有限 WMA 点的当前数据或确定性夹具，覆盖首次加载、缩放/平移、横竖屏；同时确认手势和十字光标未回归。是否将绿色填充列为产品验收项，由被阻塞的 grilling 票据决定；层叠修复本身会同时恢复两者可见性。

### 剩余风险

- `z-index: 3` 依赖当前锁定的 Lightweight Charts 4.2.3 内部层级；未来升级第三方库时必须重跑 computed-style 层叠契约，不能只假定内部最高值永远为 `2`。
- 本票据不实现修复，也不能用 Canvas 位图中存在像素代替修复后的真机合成可见性验收。
