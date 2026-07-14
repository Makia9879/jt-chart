# Android 工具链与 Pixel 虚拟机基线

## 结论

后续会话统一使用 Android Studio 已配置的 SDK `~/Library/Android/sdk` 和 Studio 内置 JBR 21，不使用当前 shell PATH 中 Homebrew 提供的 `sdkmanager`/`avdmanager`。已存在且已启动的 `Pixel_9_Pro_XL` 可从命令行通过 ADB 稳定连接，Logcat 和截图命令已实测通过。

AVD 本身不使用 JDK 或 Gradle；JDK/Gradle 是后续 APK 构建基线。当前机器已有可运行的 Gradle 8.7 分发包，与 Studio JBR 21.0.10 组合已实测启动成功。Android 工程还未创建，因此 AGP/Kotlin/Compose 版本应由后续架构票据一次性锁定，不将本机历史 Gradle 缓存误当成项目契约。

## 已验证环境

- 主机：Apple Silicon `arm64`，macOS 15.7.7。
- Android Studio：`Android Studio Preview 2025.3`，build `AI-253.32098.37.2534.15136351`，安装于 `/Volumes/samsung_disk_2T/Applications/Android Studio Preview.app`。
- Android Studio SDK 设置：`/Users/makia/Library/Android/sdk`；Studio 的 `android.sdk.path.xml` 与 AVD skin 路径都指向该目录。
- SDK Platform：Android 36 revision 2；同时存在 36.1，本 AVD 明确使用 `android-36`。
- Build Tools：36.1.0 已安装（37.0.0 也存在，项目不应依赖“取最新”）。
- Platform Tools / ADB：37.0.1，`adb` 为 arm64/x86_64 通用二进制。
- Emulator：37.1.7.0，arm64 二进制。
- AVD：`Pixel_9_Pro_XL`，显示名 `Pixel 9 Pro XL`，Google Play 系统镜像 `system-images;android-36;google_apis_playstore;arm64-v8a` revision 7。
- AVD 硬件：ARM64，4 vCPU，2048 MiB RAM，1344x2992，480 dpi，默认竖屏，6 GiB data partition。
- 运行时：`emulator-5554`，`sys.boot_completed=1`，Android 16 / API 36，ABI `arm64-v8a`，指纹 `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`。
- JDK：Studio 内置 JetBrains Runtime 21.0.10；当前 shell 默认是 Oracle JDK 20.0.1，不用于本项目。
- Gradle：本机已缓存 Gradle 8.7；设置 Studio JBR 21 与可写 `GRADLE_USER_HOME` 后，`gradle --version` 实测成功。

## 命令行统一环境

每个后续会话在执行 Android 命令前先设置：

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/Volumes/samsung_disk_2T/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$JAVA_HOME/bin:$PATH"
export AVD_NAME="Pixel_9_Pro_XL"
```

这会确保 `adb` 和 `emulator` 来自 Studio 的 SDK。当前 `/opt/homebrew/bin/sdkmanager` 是 Homebrew command-line tools 20.0，不得用它修改这个 SDK。当前 Studio SDK 未安装 `cmdline-tools/latest`；如后续确需 `sdkmanager`，先通过 Android Studio SDK Manager 向同一 SDK 安装 Android SDK Command-line Tools，然后仅使用：

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --list
```

Codex 受限 shell 无权写默认 `~/.gradle`，会表现为 `libnative-platform.dylib.lock (Operation not permitted)`。在受限会话里将 Gradle 缓存指向工作区可写目录；实现工程时必须将它加入 `.gitignore`：

```bash
cd /Volumes/samsung_disk_2T/openclaw_workspace/jt-chart/android-app
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
./gradlew --version
```

## 启动、连接与等待 AVD

先检查，避免重复启动：

```bash
adb devices -l
adb -s emulator-5554 emu avd name
adb -s emulator-5554 shell getprop sys.boot_completed
```

若没有运行，在普通 macOS Terminal 启动：

```bash
"$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME"
```

在 Codex 受限沙箱内，Emulator 的 Qt CPU 特征检测可能误报 `requires ... neon`；同一 arm64 二进制在沙箱外执行 `-version` 已成功。因此 agent 启动 Emulator 时应直接请求沙箱外执行，不应换用 x86 镜像或重建 AVD。

启动后机械等待开机：

```bash
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell wm size
adb shell wm density
```

`emulator-5554` 仅是本次实测串号，端口可变。存在多台设备时，先用 `adb devices -l`，再对候选 emulator 执行 `adb -s <serial> emu avd name`，以返回 `Pixel_9_Pro_XL` 为准；后续所有命令都显式加 `-s <serial>`。

## 构建、安装、Logcat 和截图

工程创建后，debug 闭环使用 Gradle Wrapper，不依赖系统 `gradle`：

```bash
cd /Volumes/samsung_disk_2T/openclaw_workspace/jt-chart/android-app
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
./gradlew --no-daemon :app:assembleDebug
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am force-stop com.makia.jtchart
adb -s emulator-5554 shell monkey -p com.makia.jtchart -c android.intent.category.LAUNCHER 1
```

Logcat：

```bash
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat -v threadtime --pid="$(adb -s emulator-5554 shell pidof -s com.makia.jtchart | tr -d '\r')"
```

如 App 未运行、`pidof` 为空，先启动 App，或临时用包名筛选完整 Logcat：

```bash
adb -s emulator-5554 logcat -d -v threadtime | rg 'com\.makia\.jtchart|AndroidRuntime'
```

截图：

```bash
adb -s emulator-5554 exec-out screencap -p > /tmp/jt-chart-pixel-9-pro-xl.png
```

实测 `exec-out screencap -p` 产生了 1,868,683 bytes PNG 数据，`logcat -d -t 5` 也能正常读取。

## 基线自检

后续会话开始实现或验收前，至少要得到以下结果：

```bash
java -version
adb version
emulator -list-avds
adb devices -l
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 shell getprop ro.product.cpu.abi
```

预期：Java 21.0.10；ADB 37.0.1；AVD 列表包含 `Pixel_9_Pro_XL`；设备状态为 `device`；SDK `36`；ABI `arm64-v8a`。任何一项不符时先修正路径或设备选择，不应通过重建 App 或降级 AVD 规避。
