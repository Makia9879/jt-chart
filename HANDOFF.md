# JT Chart Android MVP 实施交接

> 2026-07-14 实施更新：Android 工程、六源 Repository、Room/DataStore、内置 WebView 图表、Compose B 方案抽屉、失败气泡和核心状态机已经实现。普通 CI 已通过；Pixel 核心 smoke 已通过，但完整 Pixel 矩阵、release 签名门禁和小米真机门禁尚未完成。因此当前只能视为开发候选实现，不能对外标记为最终发布版。下文“尚未创建工程”等内容是实施前现场记录，不再代表当前代码状态。

## 当前结论

“纯 Android 图表 App” Wayfinder 已收敛并关闭。实施的唯一汇总规格是：

- [JT Chart Android MVP 规格](android-app/MVP_SPEC.md)

决策路线与详细证据：

- [纯 Android 图表 App 执行路线图](.issues/wayfinder/android-app/0000-纯Android图表App执行路线图.md)
- `.issues/wayfinder/android-app/tickets/`：八张已关闭子票据
- `.issues/wayfinder/android-app/assets/`：算法、六源、环境、原型、WebView、缓存、签名和验收契约

## 已完成与未完成边界

已完成：

- 八张规划票据全部关闭，最终 `MVP_SPEC.md` 已生成。
- 已确定 Compose 原生外壳、Kotlin 六源直连、APK 内置 WebView、`WebViewAssetLoader + WebMessageChannel`、Room/DataStore、B 方案设置抽屉、失败旧图与顶部置顶气泡、签名 release 和三层验收门禁。
- Pixel 9 Pro XL AVD 的 SDK/JBR/ADB/Logcat/截图工具链基线已验证。

尚未完成：

- 尚未创建实际 Android Gradle 工程、源码、fixture 或测试。
- 尚未构建、安装或验收任何 JT Chart APK。
- 尚未运行 App 层 Pixel UI/生命周期/断网/六源公网 smoke、release 验签或覆盖升级。
- 小米 17 Pro Max 当前不在线；它不阻塞实现和 Pixel 开发候选版，但阻塞最终对外交付。

不要把规划文档中的未来验收条目描述成已经通过。

## 环境事实（2026-07-14）

- Android Studio SDK：`/Users/makia/Library/Android/sdk`
- JBR：Android Studio 内置 21.0.10
- AVD：`Pixel_9_Pro_XL`，Android 16 / API 36，ARM64，1344x2992@480dpi
- 取证时设备为 `emulator-5554`、boot complete；serial 可变，后续必须按 AVD 名识别。
- 详细命令见 [Android 工具链与 Pixel 虚拟机基线](.issues/wayfinder/android-app/assets/Android工具链与Pixel虚拟机基线.md)。

## 下一步

只有用户明确授权实施后，才按 `android-app/MVP_SPEC.md` 开始：

1. 先检查工作树并读取规格和端到端验收矩阵。
2. 创建并锁定兼容 SDK 36 / JBR 21 的 Android/Gradle 工程依赖组合，完成最小 debug build 与 lint。
3. 依规格顺序实现 fixture/CI、Repository/持久化/状态机、WebView 图表、Compose UI。
4. 普通 CI 通过后执行 Pixel 门禁，再配置仓库外 release 签名。
5. Pixel 通过只能标记为开发候选版；最终用同一 SHA-256 APK 完成小米/HyperOS 门禁后才能对外交付。

如详细矩阵或历史资产中的 `dist/evidence`、`datasetKey` 等旧措辞与汇总规格冲突，以 `MVP_SPEC.md` 的 `qa-evidence`、`queryKey/datasetFingerprint` 等最终口径为准。

历史对话中的“继续”只授权完成规划，不应推断为创建工程或构建 APK 的授权。

## 现场保护

- `jt-chart/` 是独立 Git 仓库。
- 当前工作树不干净；`tests/__pycache__/test_jt_regime.cpython-313.pyc` 是既有改动，不得覆盖或还原。
- `.issues/wayfinder/android-app/`、`HANDOFF.md` 和 `android-app/MVP_SPEC.md` 是未跟踪规划产物，除非用户明确要求，不提交、不清理。
- 不读取、打印或提交 `.env`。
- release keystore、密码和私钥永远留在仓库外，不在测试日志或交接文档中出现。

接手时先执行：

```bash
cd /Volumes/samsung_disk_2T/openclaw_workspace/jt-chart
git status --short
sed -n '1,380p' android-app/MVP_SPEC.md
sed -n '1,380p' .issues/wayfinder/android-app/assets/端到端验收矩阵.md
```

## 建议流程

- 明确获得实施授权后使用 `$implement`；若用户要求测试先行，再结合 `$tdd`。
- 不需要重新开启 `$wayfinder`、重做原型或重复已关闭的官方 API 研究，除非产品范围发生变化。
