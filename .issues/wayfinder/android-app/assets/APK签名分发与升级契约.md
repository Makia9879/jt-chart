# JT Chart APK 签名、分发与升级契约

## 结论

`JT Chart` 不经应用商店分发，因此项目自行持有唯一的 app signing key，每个 release APK 都必须由该密钥签名。只有同时满足以下条件才可覆盖升级已安装版本：

- `applicationId` 始终为 `com.makia.jtchart`；
- 新 APK 的签名证书与已安装 APK 匹配；
- 新 APK 的 `versionCode` 严格大于已安装版本。

Android 官方文档说明，系统安装更新时会比较新旧版本的证书；不匹配时不能把新 APK 当作原 App 的更新。系统还会用 `versionCode` 防止降级。[1][2]

## 1. 签名身份

- 首个正式版发布前仅生成一个 release keystore 和一个 key alias；不把 debug key 用于分发。
- 建议 alias 为 `jt-chart-release`，keystore 文件名为 `jt-chart-release.jks`。alias 和文件名不是秘密，但私钥和密码是秘密。
- 密钥有效期至少 25 年；Android 官方建议有效期覆盖 App 的预期生命周期，且建议 25 年或更长。[1]
- MVP 不启用密钥轮换。所有 release 使用同一密钥，等真有密钥轮换需求时再单独设计 signing lineage，不在首版隐式引入。

## 2. Keystore 与密码契约

### 存放

- keystore 位于仓库外的用户私有目录，例如 `~/.config/jt-chart/signing/jt-chart-release.jks`；该目录建议权限 `0700`，文件建议权限 `0600`。
- keystore 不得复制到 `android-app/`、构建输出目录或 APK 分发包中。
- release APK、公开证书指纹和文件 SHA-256 可分发；私钥不可分发。Android 明确区分可共享的公钥证书与必须保密的 app signing key。[1]

### 密码注入

Gradle 脚本只读取四个 Gradle property，不写入任何实值：

```text
JT_CHART_STORE_FILE
JT_CHART_STORE_PASSWORD
JT_CHART_KEY_ALIAS
JT_CHART_KEY_PASSWORD
```

允许两种注入方式：

1. 本地人工构建：存在仓库外的 `$GRADLE_USER_HOME/gradle.properties`，文件权限设为 `0600`。
2. 无人值守构建：由受控的 secret store 注入 `ORG_GRADLE_PROJECT_JT_CHART_STORE_FILE`、`ORG_GRADLE_PROJECT_JT_CHART_STORE_PASSWORD`、`ORG_GRADLE_PROJECT_JT_CHART_KEY_ALIAS` 和 `ORG_GRADLE_PROJECT_JT_CHART_KEY_PASSWORD`。Gradle 官方将 `ORG_GRADLE_PROJECT_*` 环境变量列为向无人值守构建提供 secret project properties 的常用优选方式。[5]

禁止：

- 不在 `build.gradle.kts`、`gradle.properties`（项目级）、`local.properties`、shell script 或文档中写密码实值；
- 不通过 `-P...Password=...` 传密码，避免进入 shell history 或进程参数；
- 不在日志中输出 Gradle property 或环境变量值。

Android 官方允许用单独的 `keystore.properties` 从构建文件移除签名秘密，并要求安全保管、可从版本控制中移除。本项目采用更严格的仓库外 Gradle property 方案，但保留相同原则。[1]

### Git 防线

实施阶段必须在 `.gitignore` 加入至少：

```gitignore
android-app/keystore.properties
android-app/**/*.jks
android-app/**/*.keystore
android-app/dist/
```

忽略规则是误操作防线，不是允许把 keystore 放进仓库目录的理由。每次发布前仍需检查 `git status --short` 和已跟踪文件列表。

### 备份

- keystore 至少保留两份加密备份，放在不同的物理介质/故障域，不与 APK 分发。
- keystore password、key password 和 alias 保存在受控密码管理器，不与 keystore 的离线备份存在同一位置。
- 首次正式分发前，用一份备份在临时环境做一次恢复签名演练，核对生成 APK 的签名证书 SHA-256 指纹与基线一致。

不使用 Play App Signing 时，Google 无法取回该密钥；Android 官方明确说明，丢失 app signing key 后将失去更新原 App 的能力，且已生成的密钥不能重建。[1]

## 3. 版本契约

- 首个正式分发版：`versionName = "0.1.0"`，`versionCode = 1`。
- `versionName` 面向用户，采用 `MAJOR.MINOR.PATCH`；可以表达产品版本，不用于 Android 升降级判断。
- `versionCode` 是正整数，每个对外分发的 APK 严格递增，已用过的值永不复用，即使 APK 随后撤回也不回滚。
- 同一 `versionName` 因修复重新分发时也必须增加 `versionCode`；推荐同时增加 patch 版本，避免人工识别混淆。

Android 系统以更高的 `versionCode` 识别更新，官方建议从 1 开始并在每次发布后单调递增；`versionName` 是显示给用户的字符串。[2]

## 4. APK 生成与命名

唯一正式构建入口：

```bash
./gradlew clean assembleRelease
```

Gradle release signing config 必须在四个 property 任何一个缺失时立即失败，不得回退到 debug 签名或生成未签名的“伪 release”。

分发文件名固定为：

```text
JT-Chart-v{versionName}-c{versionCode}-release.apk
```

首版是：

```text
JT-Chart-v0.1.0-c1-release.apk
```

文件名同时携带人可读版本和 Android 内部版本，但以 APK manifest 中的值为准，不信任文件名。

## 5. 发布前校验

设 `APK=JT-Chart-v0.1.0-c1-release.apk`。发布人必须执行：

```bash
apksigner verify --verbose --print-certs "$APK"
apkanalyzer manifest application-id "$APK"
apkanalyzer manifest version-name "$APK"
apkanalyzer manifest version-code "$APK"
apkanalyzer manifest min-sdk "$APK"
apkanalyzer manifest target-sdk "$APK"
apkanalyzer manifest debuggable "$APK"
shasum -a 256 "$APK"
```

必须同时满足：

- `apksigner verify` 成功；其 `--print-certs` 输出的 signer certificate SHA-256 digest 与第一个正式版建立的基线完全一致；
- application ID 为 `com.makia.jtchart`；
- version name/code 与本次发布记录和文件名一致；
- min SDK 为 29；target SDK 与当前已确定构建基线一致；
- `debuggable` 为 `false`；
- SHA-256 重新计算后与发布附带值一致。

`apksigner verify` 是 Android SDK 官方的 APK 签名验证入口，`--print-certs` 显示签名证书信息。`apkanalyzer` 官方支持从 manifest 读取 application ID、version name/code、min/target SDK 和 debuggable 状态。[3][4]

## 6. 分发包契约

每次分发的目录或压缩包只包含：

```text
JT-Chart-v0.1.0-c1-release.apk
SHA256SUMS.txt
RELEASE_NOTES.md
```

`SHA256SUMS.txt` 使用标准的“64 位小写十六进制摘要 + 两个空格 + 文件名”格式。`RELEASE_NOTES.md` 至少记录：

- `versionName` 和 `versionCode`；
- application ID；
- signer certificate SHA-256 digest（公开证书指纹，不是私钥）；
- APK SHA-256；
- 构建日期、对应 Git commit 和主要变更；
- 支持的 min/target SDK；
- 覆盖安装与校验命令。

发布包严禁包含 `.jks`、`.keystore`、`keystore.properties`、`gradle.properties`、`.env` 或任何密码/私钥。

## 7. 覆盖升级验收

首次安装可用：

```bash
adb install "JT-Chart-v0.1.0-c1-release.apk"
```

后续版本的发布门禁必须在保留已安装 App 数据的前提下执行：

```bash
adb install -r "JT-Chart-v{newVersionName}-c{newVersionCode}-release.apk"
```

ADB 官方文档定义 `-r` 为“重新安装已有 App 并保留数据”。门禁应验证：安装成功、设置和最后成功行情缓存仍在、App 可启动且版本号正确。不得在正常升级验收中使用 `adb uninstall`，因为这会绕过签名兼容和数据保留检查。[6]

首个正式版必须留存一份可重复安装的基线 release APK；从第二个正式版开始，每次发布都在 Pixel 9 Pro XL 虚拟机上从上一个 release 做真实覆盖升级。小米 17 Pro Max 上线后，在最终发布关卡重复同一流程。

## 8. 密钥丢失或泄露

- **丢失密钥**：无法再生成能覆盖升级 `com.makia.jtchart` 已安装版本的 APK。只能发布新 application ID 的新 App，用户必须单独安装，旧 App 私有数据不会自动迁移。
- **密钥泄露**：攻击者可签名恶意 APK 来尝试替换真实 App。立即停止使用该密钥发布，保留证据，并单独制定转移/密钥轮换方案；不能把“重新生成一个 keystore”当作无感修复。

Android 官方强调，私钥是签名所有后续版本的必需品，丢失后无法重建；私钥落入他人手中可能被用来签名恶意替换版本。[1]

## 9. 实施阶段必须落地的项目内产物

本契约仅做决策，实施会话应在 `android-app/` 落地：

- release `signingConfig` 和缺少 secret 时的 fail-fast 校验；
- `.gitignore` 防线；
- 一个不含秘密的 release 构建/校验脚本；
- `RELEASE.md` 或等价说明，记录密钥创建、备份、构建、验签、生成哈希与覆盖安装流程，但不含任何秘密实值。

## 官方资料

1. Android Developers, **Sign your app**: <https://developer.android.com/studio/publish/app-signing>
2. Android Developers, **Version your app**: <https://developer.android.com/studio/publish/versioning>
3. Android Developers, **apksigner**: <https://developer.android.com/tools/apksigner>
4. Android Developers, **apkanalyzer**: <https://developer.android.com/tools/apkanalyzer>
5. Gradle User Manual, **Build Environment Configuration**: <https://docs.gradle.org/current/userguide/build_environment.html>
6. Android Developers, **Android Debug Bridge (adb)**: <https://developer.android.com/tools/adb>

访问日期：2026-07-14。
