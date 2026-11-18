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
- `WebSessionService.postReply` 委派给独立的 `ReplySubmission` / `ReplyPage`：每次操作最多一次 POST，取消 403 自动重发并禁止网络库重放；对页面延迟更新和 POST 传输异常最多进行三轮只读确认。使用会话 Cookie 快照、限定表单与同源话题跳转，按新增回复 ID、作者和完整正文确认，无法确认时保留草稿并提示先到网页查看。协程取消会停止在途请求，不改其他网页操作。
- 新增基于 MockWebServer 5.5.0 的回帖回归测试，覆盖真实本机 HTTP 请求、延迟/超时确认、重定向、禁止重复 POST、分页、取消和页面误判防护；不使用真实账号发帖。复杂富文本无法可靠匹配时保守返回待确认，不通过删除 Markdown 符号或 URL 子串比较来判定成功。
- 新增管理员移动主题：`TopicMovePage` / `TopicMoveService` 校验可信管理链接及真实表单，单次确认最多一个 POST，随后只读核查节点；`TopicMoveRepository` 按网页会话与话题缓存权限，账号变更和过期响应不会恢复旧权限。列表、搜索和详情共用原生移动面板，支持常用节点、别名搜索和备注。
- 已确认节点以本地元信息覆盖旧 API、预览和离线快照的节点显示，原节点列表过滤已移出的条目；未重写或删除收藏与离线正文。管理权限不落盘。新增移动协议及仓库状态单测，使用人工页面与本机 HTTP，不执行真实管理操作。
- `gradle/wrapper/gradle-wrapper.properties` 增加官方 Gradle 9.7.1 分发包 SHA-256 校验；导入时 Wrapper JAR SHA-256 也已与 Gradle 官方发布值核对。
- README 增加本仓构建说明入口；补充 Wrapper 许可证文本。
- 根 `.gitignore` 增加 Android 签名密钥保护。
- 根 `.github/workflows/android-build.yml` 独立执行 Android 单元测试、Lint 和 Debug APK 构建；`.github/workflows/ios-build.yml` 仅构建 iOS 未签名 IPA。两个入口都只手动触发，构建 Android 不会启动或等待 macOS runner。

## 构建产物与签名

本仓 Actions 构建 `:app:assembleDebug`，包名为 `com.vibe.v2ex.debug`，无需配置发布密钥。Debug APK 可安装，但不同 CI 运行的临时签名密钥可能不同，覆盖安装可能失败。卸载旧 Debug 版后重装会清除该应用的本地数据，请先备份。

上游 `:app:assembleRelease` 在缺少 `keystore.properties` 时会退回 Debug 签名；**这不是正式发布签名**，本仓 Actions 不调用此变体。正式分发或稳定覆盖升级需要自行配置并妥善保存签名密钥，不得将密钥、密码或 `keystore.properties` 提交到仓库。

两个独立 Actions 入口的用法见[根 README](../README.md#通过-github-actions-构建-ipa-和-apk)；新增 Android workflow 合入默认分支后即可从 Actions 手动运行。
