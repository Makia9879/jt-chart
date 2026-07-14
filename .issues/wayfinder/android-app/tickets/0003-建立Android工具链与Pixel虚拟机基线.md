---
id: wf-android-app-0003
title: 建立 Android 工具链与 Pixel 虚拟机基线
parent: wayfinder-jt-chart-android-app
labels:
  - wayfinder:task
status: closed
assignee: /root/android_baseline
blocked_by: []
---

## Question

当前 Android Studio 中已准备的 Pixel 9 Pro XL AVD 实际使用哪个 SDK、ABI、系统镜像、JDK 和 Gradle 组合？需完成一份可在命令行机械执行的环境基线，使后续会话能够启动/连接该 AVD、构建 APK、ADB 安装、读取 Logcat 和截图；如 Android Studio 与 shell 使用不同 SDK 路径，需明确统一方案。

## Resolution

已完成实机环境取证与可机械执行基线：[Android 工具链与 Pixel 虚拟机基线](../assets/Android工具链与Pixel虚拟机基线.md)。

关键结论：

- 统一使用 Android Studio 已配置的 `~/Library/Android/sdk` 和 Studio 内置 JBR 21.0.10，不使用 shell PATH 中 Homebrew 的 command-line tools。
- `Pixel_9_Pro_XL` 是 Android 16 / API 36 Google Play ARM64 AVD，系统镜像 revision 7，1344x2992@480dpi；实测已开机且 ADB 状态为 `device`。
- ADB 连接、AVD 身份校验、Logcat 和 PNG 截图数据读取均已实测通过；资产中给出了构建、安装和启动 App 的机械命令。
- Gradle 8.7 + JBR 21.0.10 已验证能启动；受限 Codex 会话需将 `GRADLE_USER_HOME` 设为工作区可写目录。AGP/Kotlin/Compose 尚无项目事实，留给架构票据锁定，避免伪造“当前组合”。
- Codex 受限沙箱内启动 Emulator 会因 CPU 特征检测误报 `neon`；agent 需请求沙箱外启动，无需更换镜像或 AVD。
