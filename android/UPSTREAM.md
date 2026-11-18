# Android 上游来源

本目录是 [xinghelee/v2ex-android](https://github.com/xinghelee/v2ex-android) 的源码快照，原作者和维护者为上游仓库所有者。此次整合不改变原作者归属。

- 导入提交：[`dc5a53ba6d0330eda30204195a534433967d7a5b`](https://github.com/xinghelee/v2ex-android/tree/dc5a53ba6d0330eda30204195a534433967d7a5b)
- 导入日期：2026-11-18
- 导入范围：该提交的全部 146 个受版本控制文件；导入时逐文件核对 Git blob 哈希并保留可执行位。未导入嵌套 Git 仓库、缓存或签名凭据。
- 更新方式：后续同步需对照上述提交与上游新版本审查差异，保留本仓改动；本目录不是自动跟随上游的子模块。

## 许可说明

在上述提交中，上游没有提供项目级 `LICENSE`、`COPYING` 或 `NOTICE`，README 也未声明项目许可证。**公开可读不等于已授予复制、修改或再分发许可**；公开再分发前应向权利人确认授权。本仓不擅自为 Android 应用代码添加许可证。

Gradle Wrapper 脚本已有的版权/Apache-2.0 声明仍保留；Wrapper 的许可证见 [`gradle/wrapper/LICENSE`](gradle/wrapper/LICENSE)。Wrapper 及其他第三方组件的许可不代表整个应用采用相同许可。

## 本仓整合差异

- 整体放入 `android/`，iOS 工程保留根路径；应用包名和版本号不变。
- `NotificationPushNotifier.kt` 将发送通知的 `runCatching` 改为显式捕获 `SecurityException`，保留权限检查后的撤销兜底，并修复上游 `MissingPermission` Lint 错误；未关闭或忽略该检查。
- `gradle/wrapper/gradle-wrapper.properties` 增加官方 Gradle 9.7.1 分发包 SHA-256 校验；导入时 Wrapper JAR SHA-256 也已与 Gradle 官方发布值核对。
- README 增加本仓构建说明入口；补充 Wrapper 许可证文本。
- 根 `.gitignore` 增加 Android 签名密钥保护。
- 根 `.github/workflows/ios-build.yml` 增加 Android 单元测试、Lint 和 Debug APK 构建，原 iOS 未签名 IPA 流程保留。

## 构建产物与签名

本仓 Actions 构建 `:app:assembleDebug`，包名为 `com.vibe.v2ex.debug`，无需配置发布密钥。Debug APK 可安装，但不同 CI 运行的临时签名密钥可能不同，覆盖安装可能失败。卸载旧 Debug 版后重装会清除该应用的本地数据，请先备份。

上游 `:app:assembleRelease` 在缺少 `keystore.properties` 时会退回 Debug 签名；**这不是正式发布签名**，本仓 Actions 不调用此变体。正式分发或稳定覆盖升级需要自行配置并妥善保存签名密钥，不得将密钥、密码或 `keystore.properties` 提交到仓库。

双平台 Actions 用法见[根 README](../README.md#通过-github-actions-构建-ipa-和-apk)。
