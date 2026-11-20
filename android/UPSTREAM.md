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
- 回帖表单、分页和回复确认同时支持桌面 `#Main` 与手机 `#Wrapper > div.content`，仅使用唯一可信主区域，排除正文、附言、回复和侧栏中的伪造控件。GET、唯一 POST 和只读确认统一使用登录时保存的实际 UA；旧会话缺少 UA 时保留兼容回退，非法 UA 在联网前拒绝。明确挑战页提示网页验证，不再误报为缺少回复表单；错误 Snackbar 与输入条共同避让键盘，失败保留草稿。
- 新增基于 MockWebServer 5.5.0 的回帖回归测试，覆盖真实本机 HTTP 请求、延迟/超时确认、重定向、禁止重复 POST、桌面/手机分页、会话 UA、挑战页、取消和页面误判防护；不使用真实账号发帖。复杂富文本无法可靠匹配时保守返回待确认，不通过删除 Markdown 符号或 URL 子串比较来判定成功。
- 新增管理员移动主题：`TopicMovePage` / `TopicMoveService` 校验可信管理链接及真实表单，单次确认最多一个 POST；正常提交响应即提示「移动请求已提交」，取消阻塞式三轮结果轮询，明确拒绝及网络/服务器异常仍单独处理。`TopicMoveRepository` 按网页会话与话题缓存权限，账号变更和过期响应不会恢复旧权限。仅详情右上角菜单保留入口并执行单次权限检查，所有列表和搜索不再逐项探测。
- 移动面板常用节点支持多行、自定义增删排序、本机持久化与恢复官网默认，不设固定数量限制；搜索结果在输入框下方限高滚动，选择后收起，提交按钮固定在底部，二次确认居中。
- 提交后的目标节点仅作 5 分钟内存乐观显示，详情可额外执行一次非阻塞只读校正，失败不弹警告；只有真实读取的节点可以落盘（最多 200 条，5 分钟有效），不再为列表覆盖逐项请求。未重写或删除收藏与离线正文，管理权限不落盘。新增移动协议、偏好与仓库状态单测，使用人工页面与本机 HTTP，不执行真实管理操作。
- 手机话题页缺少 MOVE 链接时，通过只读 GET 获取并严格验证同源、同话题的移动表单作为权限证据；无效表单、登录跳转、验证页和跨源响应均不能授予入口。原有每次操作最多一次 POST 的约束不变。
- 移动提交响应允许同源、同话题的回复锚点跳转（如 `/t/123#reply27`），避免正常 302 被误报；仍拒绝跨源、其他话题和异常查询参数，不跟随提交跳转或重放 POST。
- 首页顶部标签改为独立 `HomeTabsStore` 配置：全部/最热/R2/关注与自选节点均可增删、排序，最多 15 项、至少一项；初次迁移原可见节点，后续官网收藏同步不覆盖自定义顺序。编辑支持搜索节点、取消和恢复默认。
- 首页公开列表（全部、最热、R2、节点）统一由 `PublicFeedService` 只读加载：隔离登录 Cookie/PAT，统一网页 UA，禁用 HTTP 缓存并为每次加载生成独立查询参数，校验状态、挑战页和同列表跳转；保留官网排序及原分页。刷新失败保留旧列表/游标，但显示失败原因和重试入口，不再静默保留旧内容。新增连续刷新、匿名请求、分页/跳转、取消与失败状态回归。
- 首页「关注」改用当前网页会话读取 `/my/following?p=N`，展示关注用户的话题，保留官网顺序与分页，不再合并收藏节点或回退公开最新列表。独立认证 GET 限制同源跳转，区分登录、验证、未知结构和明确空列表；只使用会话内存快照，不读写公共磁盘缓存。账号切换清理状态并拒绝过期响应。新增标签存储、关注解析/请求与会话隔离回归测试。
- 网页登录恢复系统 WebView 默认 UA，显式支持第三方 Cookie，移除每次打开时异步清 Cookie 和重组重复导航。新增手动刷新、登录帮助及确认后的重置；退出账号同时清理内置浏览器状态。首次登录在当前 WebView 内只读、禁缓存打开一次 `/settings`，以同源设置页正常加载、无明确登录/两步验证/挑战页及匹配的账号确认，不再依赖独立 OkHttp 请求；删除设置页容器、POST 表单、once 和个人资料字段门槛，避免通用设置分栏导致误判。仅传账号与登录/挑战标记，不读取输入值，不依赖普通退出链接。HTTP/网络/证书错误与旧页面回调不能保存账号，固定诊断码不泄露凭据或正文。确认结果受页面生命周期、取消和账号版本保护，确认时保存最新 Cookie 和实际 UA；已存会话的后台只读复查仍保守区分明确退出与 Cloudflare/限流/网络无结论，旧安装不强制注销。新增结构证据、WebView 生命周期与 MockWebServer 回归，不声称自动化测试可证明真实挑战通过。
- `gradle/wrapper/gradle-wrapper.properties` 增加官方 Gradle 9.7.1 分发包 SHA-256 校验；导入时 Wrapper JAR SHA-256 也已与 Gradle 官方发布值核对。
- README 增加本仓构建说明入口；补充 Wrapper 许可证文本。
- 根 `.gitignore` 增加 Android 签名密钥保护。
- 根 `.github/workflows/android-build.yml` 独立执行单元测试、Release Lint 和 APK 构建，仅上传 Release APK；报告产物同时保留 R8 混淆映射。`.github/workflows/ios-build.yml` 仅构建 iOS 未签名 IPA。两个入口都只手动触发，构建 Android 不会启动或等待 macOS runner。

## 构建产物与签名

本仓 Actions 构建 `:app:assembleRelease`，运行 `:app:testDebugUnitTest` 与 `:app:lintRelease`。APK 开启 R8 混淆和资源压缩，包名为 `com.vibe.v2ex`，产物为 `app/build/outputs/apk/release/app-release.apk`；不再构建或上传 Debug APK。旧 Debug 版包名为 `com.vibe.v2ex.debug`，可与 Release 版并存，但登录和本地数据不会自动迁移。

工程在缺少 `keystore.properties` 时会退回 Debug 签名；当前 Actions 沿用此回退，因此 APK 可安装，但 **Release 变体不是正式发布签名**。不同 CI 运行的临时密钥可能不同，覆盖升级可能失败，也不能覆盖上游签名的同包名应用。卸载会清除应用本地数据，请先备份。正式分发或稳定覆盖升级需要自行配置并妥善保存签名密钥，不得将密钥、密码或 `keystore.properties` 提交到仓库。

两个独立 Actions 入口的用法见[根 README](../README.md#通过-github-actions-构建-ipa-和-apk)；新增 Android workflow 合入默认分支后即可从 Actions 手动运行。
