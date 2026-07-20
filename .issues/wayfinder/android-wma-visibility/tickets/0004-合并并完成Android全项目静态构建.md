---
id: wf-android-wma-visibility-0004
title: 合并并完成 Android 全项目静态构建
parent: wayfinder-jt-chart-android-wma-visibility
labels:
  - wayfinder:task
status: closed
assignee: /root/wma_integration
blocked_by:
  - wf-android-wma-visibility-0003
---

## Question

实现补丁合入当前 JT Chart 工作树后，是否完整保留本票据地图的功能边界并通过 JavaScript 单元测试、Android JVM 测试、Lint 和 debug APK 构建？需记录合并来源、冲突处理、完整命令与结果，任何失败不得带入设备部署阶段。

## Resolution

已将实现分支 `codex/android-wma-overlay` 的单一提交 `ee6e1a496c64f0ff232f1879b7c81215ebfd39d1` 无交互 cherry-pick 到主工作树 `master`，生成集成提交 `4a1781c03bc6b39aff3411fdc3e63e1c7890359c`（`Fix Android WMA overlay layering`）。cherry-pick 无冲突；未跟踪的 `.issues/wayfinder/android-wma-visibility/` 全程保留，未触碰 `.env` 或无关文件。

### 集成范围核对

- 相对合入前 `994b41f29bb7f6584b390ecb20dd326f2bd3ecc2`，只修改 `android-app/app/src/main/assets/chart/chart.css`、`renderer.js` 与 `android-app/app/src/test/js/renderer.test.js`，共 `144 insertions(+), 5 deletions(-)`。
- 保留票据 0001 的白色 WMA 与绿色熊市填充共同验收边界，以及票据 0002/0003 的 `z-index: 3`、`pointer-events: none` 和运行时层叠契约；没有修改算法、默认参数、手势、十字光标或第三方库。
- `git diff --check HEAD^ HEAD` 通过；集成后 `git status --short --branch` 仅显示 `master` 相对 `origin/master` ahead 1 以及本地图目录未跟踪，不存在未提交的已跟踪源码改动。

### 本机工具链与离线约束

所有 Gradle 命令均使用以下本机既有资源，并显式传入 `--offline --no-daemon`，未下载或安装任何工具链：

```text
JAVA_HOME=/Volumes/samsung_disk_2T/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home
ANDROID_HOME=/Users/makia/Library/Android/sdk
ANDROID_SDK_ROOT=/Users/makia/Library/Android/sdk
GRADLE_USER_HOME=/Volumes/samsung_disk_2T/openclaw_workspace/jt-chart/android-app/.gradle-user-home
ANDROID_USER_HOME=/Volumes/samsung_disk_2T/openclaw_workspace/jt-chart/android-app/.android-user-home
```

### 完整验证命令与结果

在 `android-app/` 执行：

1. `node --test app/src/test/js/*.test.js`：通过，`18/18`，包括非空覆盖层白色 2px stroke、绿色 fill、真实发布 CSS 层叠/指针契约与失败用例。
2. `env JAVA_HOME='…' ANDROID_HOME='…' ANDROID_SDK_ROOT='…' GRADLE_USER_HOME='…' ANDROID_USER_HOME='…' ./gradlew --offline --no-daemon testDebugUnitTest`：`BUILD SUCCESSFUL in 24s`，39 tasks。
3. 同一环境执行 `./gradlew --offline --no-daemon lintDebug`：`BUILD SUCCESSFUL in 33s`，34 tasks；HTML 报告为 `android-app/app/build/reports/lint-results-debug.html`。
4. 同一环境执行 `./gradlew --offline --no-daemon assembleDebug`：`BUILD SUCCESSFUL in 12s`，43 tasks。
5. 在仓库根执行 `git diff --check HEAD^ HEAD`：通过；并核对提交统计、文件清单与工作树状态。

Gradle 输出中的 `/Users/makia/.android/analytics.settings` 不可写与 FSEvents stream 初始化失败属于当前受限执行环境的非致命遥测/文件监听警告；三个构建均以成功退出码完成。

### Python 回归判断

README 将根目录 Python 测试归入 Web 图表/后台通知形态，而此次差异只涉及 APK 内置的 Android WebView 图表 CSS、JS 渲染契约及对应 Node 测试，没有修改 `chart_server.py`、`jt_shared.py`、`jt_regime_worker.py`、根 Web 页面或 `tests/`。因此根 Python 回归不属于本补丁的受影响测试面，本票据未运行；Android 的共享图表语义已由全量 JS 与 Android JVM/Lint/assemble 覆盖。

### 产物与 Step 3 注意事项

- debug APK：`android-app/app/build/outputs/apk/debug/app-debug.apk`（约 32 MiB）。
- SHA-256：`d54e57f9e4e7331d2b8f2bb2c3ed9e64238d934fe6a0236bbaf76dee4f11b542`。
- 本票据未安装 APK、未操作设备。Step 3 必须安装本次哈希对应 APK，在 Android 16 真机上用至少 200 根 K 线且能产生有限 WMA 的数据验证首次加载、缩放/平移、横竖屏；同时验证白线与绿色填充可见、手势/十字光标正常，并通过 CDP 复核覆盖层 computed `z-index=3`、内部最高层 `2`、`pointer-events:none`。
- 剩余风险是该层级契约绑定当前 Lightweight Charts 4.2.3；静态测试不能替代 WebView 最终合成像素证据，未来升级依赖需重新取证。
