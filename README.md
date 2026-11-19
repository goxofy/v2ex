# v2Explore

> Way to Explore

面向个人使用的 V2EX iOS 客户端，支持 **iPhone 与 iPad**，最低 iOS 18。使用 SwiftUI 构建，在 iOS 26 上启用原生 Liquid Glass 组件（低版本自动降级为磨砂材质），数据来自 V2EX API、网页会话和 sov2ex 搜索。iPad 上列表与正文收窄为居中的可读栏宽，横竖屏均可使用。

本仓库也包含 **Android 8.0+** 客户端，源码位于 [`android/`](android/)，来自 [xinghelee/v2ex-android](https://github.com/xinghelee/v2ex-android)。Android 功能说明见[独立 README](android/README.md)，来源、固定版本及许可状态见 [`android/UPSTREAM.md`](android/UPSTREAM.md)。下方功能介绍以 iOS 版为主，两端不是共享业务代码的同一个应用工程。

## 下载

- **本仓 IPA / APK**：分别通过 [Build iOS App](https://github.com/goxofy/v2ex/actions/workflows/ios-build.yml) 和 [Build Android App](https://github.com/goxofy/v2ex/actions/workflows/android-build.yml) 手动构建，步骤和签名限制见[构建说明](#通过-github-actions-构建-ipa-和-apk)。
- **上游 iOS TestFlight 公测**：[testflight.apple.com/join/jUBsFk9u](https://testflight.apple.com/join/jUBsFk9u)，不是本仓 Actions 的产物。
- **上游 Android 发行版**：[xinghelee/v2ex-android Releases](https://github.com/xinghelee/v2ex-android/releases/latest)（Android 8.0+），与本仓 Debug APK 的签名和包名不同。

## 截图

<p align="center">
  <img src="docs/screenshots/home-hd.png" alt="V2EX 首页" width="24%"/>
  <img src="docs/screenshots/topic-hd.png" alt="V2EX 话题详情" width="24%"/>
  <img src="docs/screenshots/nodes-hd.png" alt="V2EX 节点目录" width="24%"/>
  <img src="docs/screenshots/profile-hd.png" alt="V2EX 个人页面" width="24%"/>
</p>

<p align="center">
  <img src="docs/screenshots/ipad-dual-pane.png" alt="v2Explore iPad 双栏阅读与讨论轨道" width="72%"/>
</p>

## 功能

- **首页**：全部、最热、关注和快捷节点，多分类横向切换并分别记忆滚动位置；社区脉搏聚合当前活跃节点与回复分布
- **话题**：正文、代码、引用、列表与图片渲染，楼层回复、只看楼主、阅读数、收藏、离线与长帖分页；讨论轨道支持快速跳转关键楼层
- **节点**：完整节点搜索与分类目录，可编辑、排序和增删首页快捷节点，支持节点话题排序与分页
- **账户**：账号密码、验证码、两步验证或网页登录；会话仅保存在本机 Keychain
- **通知**：Personal Access Token 驱动，回复、@、感谢分类筛选，支持未读状态和删除
- **搜索与雷达**：sov2ex 全文索引（话题/回复/用户/节点），命中高亮；可持久追踪关键词、节点与用户
- **个人**：个人资料、话题统计、网页收藏同步、稍后读、我的话题与回复、官网屏蔽名单同步、用户屏蔽与取消屏蔽（需网页登录）
- **收藏资料库**：自定义收藏夹、本地标签与收藏备注，远程收藏合并后仍保留本地整理信息
- **写作**：多份 Markdown 草稿、节点选择和格式工具栏，新话题与逐帖回复草稿均自动保存
- **阅读与外观**：五套主题配色、明暗模式、正文字号、行距和等宽字体可调，可记忆阅读进度并自动离线关注节点
- **系统智能**：iOS 27 设备端讨论摘要；不可用时可由用户配置 OpenAI 兼容 API，另有 Siri/快捷指令与 Spotlight 集成
- **交互**：原生悬浮标签栏随滚动收起，列表下拉刷新，点击页面空白处收起键盘

### 管理员移动主题（iOS / Android）

- 需要具有相应权限的 **V2EX 网页登录会话**。App 逐话题核实官网权限：可信头部的管理链接可以作为证据；没有该链接时，再只读获取同话题的移动页，只有完整、同源的合法管理表单才能授予权限。这样不依赖手机话题页一定渲染桌面版 MOVE 链接。仅在进入话题详情时检查该话题权限，有权后在右上角 `⋯` 菜单显示「移动主题」；首页、节点、搜索、收藏等列表不显示移动按钮，也不逐项探测权限。支持官网桌面和手机布局。仅登录、配置 PAT、公开 MOD 标识或用户名匹配都不能获得此入口。
- **iOS 入口不显示时**：打开「设置 → 网络 → 网页权限诊断」，输入话题 ID 后检查。此页面只发 GET，不执行移动；会显示检查阶段及固定原因码（例如 `FORM_HTTP_403`、`TOPIC_STRUCTURE`），区分请求受限、会话失效与解析失败，而不是一律认定没有权限。检查成功会更新同一份入口权限状态。诊断不展示或上传 Cookie、用户名、HTML 和隐藏验证字段。
- 常用节点多行展示，默认使用官网完整列表；点「编辑」可搜索添加、删除和调整顺序，不限制为四个或固定几个。自定义配置按站点保存在本机，支持清空或恢复官网默认，不上传。
- 目标节点按名称、英文标识或别名本地搜索，结果紧邻搜索框，在限高区域滚动，不截断为前 60 项。选中后收起结果和键盘，展示已选目标。可选备注位于下方，主提交按钮固定在底部；二次确认居中显示「原节点 → 目标节点」。打开面板和提交前仍会重新核实权限；当前节点变化时停止提交，需重新打开并核对。
- 每次确认最多发送 **一次 POST**，不会因超时、重定向或服务器错误自动重复移动。正常提交响应后关闭面板，轻提示「移动请求已提交」，不再阻塞等待三轮网页确认，也不显示通用的“无法确认移动结果”。提示代表提交响应正常，不代表已经独立核实最终节点。明确拒绝、登录失效、验证页及网络/服务器异常仍会提示；提交异常时禁止本次面板再次提交，需先到网页查看。关闭面板不代表撤销操作。
- 权限缓存仅在内存中按会话、话题及站点隔离，60 秒内可复用；详情本次访问的入口状态保留至退出或会话变化，不持续轮询。退出/切换账号后失效。权限探测同话题去重、最多两个并发。无法读取权限、离线或页面结构不可信时不授予入口。
- 正常提交后，详情、列表及搜索节点标签短期显示目标节点，原节点列表暂时过滤已移出条目。此乐观状态仅在内存保留最多 5 分钟；详情额外执行一次非阻塞只读刷新来校正，失败不弹警告，不轮询。只有实际读取的节点才可持久保存（最多 200 条、5 分钟有效），过期回归普通列表数据；列表不会为这些状态逐项请求网页。**不持久保存管理权限**，不把未核实的目标写入永久缓存，不删除用户收藏和离线正文。
- 默认使用 `https://www.v2ex.com`，不依赖测试域名。iOS 沿用已有自定义 HTTPS 站点设置；不会根据页面跳转把 Cookie 发往其他站点。

测试使用人工页面和本机 HTTP 服务，不会移动真实帖子。管理员与普通账号的真机验收仍需自行完成，建议先在允许测试的话题上核对网页结果。完整 iOS App 构建需要下文所述的新 Xcode SDK，独立网络测试不等于完整 App 编译。

## 技术要点

| 模块 | 说明 |
| --- | --- |
| API 1.0 | 公开接口：话题、回复、节点、成员、全部节点 |
| API 2.0 | Personal Access Token：通知、个人资料、长帖分页、删除通知 |
| 网页会话 | App 内回复、收藏同步、关注节点同步；Cookie 只存储在 Keychain |
| sov2ex | 社区全文索引，V2EX 无官方搜索接口 |
| iOS 26 | 原生 Tab、`safeAreaBar`、`glassEffect`、边缘滚动效果与标签栏自动收起；低版本经 `iOS26Compat` 自动降级（`safeAreaInset`、`ultraThinMaterial`、`borderedProminent`） |
| iOS 27 | Foundation Models 设备端摘要、App Intents/Siri 深链与 Spotlight 语义入口（渐进增强） |

节点关注：未登录时保存在本机；网页登录后，关注和取消关注会提交到 V2EX 网页接口，确认官网状态后更新本地，失败时保留原状态并提示。设置中的「自动导入网页关注节点」只控制网页收藏列表的导入，不影响手动关注操作写入官网；此前的本地关注不会自动批量上传。
| AI 回退 | 用户可选配置 DeepSeek、硅基流动、OpenAI 或任意 OpenAI 兼容 API；Key 仅存 Keychain |
| 多设备 | iPhone 竖屏、iPad 全方向；宽屏内容自动居中为 720pt 可读栏 |
| iPad 双栏 | 宽屏（横屏/大窗）话题详情正文与楼层回复左右分栏、各自独立滚动 |
| 渲染 | 自研轻量 HTML 解析（段落/行内/图片提取），替代 NSAttributedString 方案 |
| 存储 | Keychain（Token）、UserDefaults（设置）、磁盘缓存（离线包） |
| 内容治理 | 首启条款闸门、举报与屏蔽，命中内容即时且持久地从全部列表移除 |

一些工程决策：

- **下拉刷新**：统一封装系统 `refreshable`，只在当前可见列表响应，避免多个分页容器同时争抢手势
- **键盘收起**：窗口级点击捕获不拦截按钮、链接和滚动手势，所有页面点击空白处均可结束编辑
- **图片渲染**：`AsyncImage` 必须显式 frame，否则按原图尺寸布局被裁剪；帖子图片由解析器从 `<p>` 内提取为独立 block
- **通知头像**：API 2.0 通知的 `member` 只带 `username`，用 v1 接口按用户补齐头像（内存缓存防刷新丢失）
- **阅读数**：API 不提供 views 字段，从话题页抓取解析（`N views` / `N 次点击`），失败静默隐藏
- **列表性能**：话题列表用 `LazyVStack` 惰性构建，避免分页累积后 AsyncImage 下载风暴

## 构建

### 通过 GitHub Actions 构建 IPA 和 APK

1. 在 **Actions** 中选择要构建的平台，再点击 **Run workflow**：
   - **Build iOS App**（`ios-build.yml`）：只构建未签名 IPA，使用 macOS runner。
   - **Build Android App**（`android-build.yml`）：只构建 Debug APK，使用 Ubuntu runner。
2. 选择需要构建的分支并运行。需要两端产物时，分别启动两个 workflow；不会默认同时构建。
3. 等待对应 workflow 成功，在各自运行页面底部的 **Artifacts** 下载：
   - iOS：`V2EX-unsigned-ipa-<提交 SHA>`，解压得到 `V2EX-unsigned.ipa`。
   - Android：`V2EX-debug-apk-<提交 SHA>`，解压得到 `app-debug.apk`；`V2EX-android-reports-<提交 SHA>` 为单元测试和 Lint 报告。

两个 workflow 独立触发、独立显示结果，互不调用或等待。**构建 Android 不会启动 iOS，也不受 macOS runner 排队失败影响**；单独构建 iOS 时仍可能遇到 GitHub 的 macOS 容量限制，拆分并不会消除这一平台限制。产物保留 **7 天**；构建入口仅手动触发，不在每次 push 时消耗 runner，也不会自动发布 TestFlight 或 GitHub Release。

> **签名限制：** IPA 未签名，需要自行签名侧载，不能直接安装普通 iPhone。APK 已用 Debug 密钥签名，可安装到 Android 8.0+，包名为 `com.vibe.v2ex.debug`，不是正式发行版。不同 Actions 运行的临时 Debug 密钥可能不同，覆盖升级可能失败；卸载后重装会清除该应用数据，请先备份。它不能替代原作者签名的正式版本。正式分发或稳定覆盖升级需要另行配置并保存自己的签名密钥；不要将密钥或密码提交到仓库。

**首次启用：** 新增的 `android-build.yml` 需要先合入默认分支 `main`，才会出现在 Actions 的手动运行列表中。若仍在整合分支，先合并对应 PR；不要把“尚未登记工作流”误认为构建失败。

合入默认分支后，也可以分别从命令行触发；`--ref` 可改为包含对应 workflow 的其他分支：

```bash
# 只构建 iOS
gh workflow run ios-build.yml --repo goxofy/v2ex --ref main

# 只构建 Android
gh workflow run android-build.yml --repo goxofy/v2ex --ref main
```

### 本地 iOS 构建

部署目标为 **iOS 18.0**；当前工程此前已在 Actions 的 **Xcode 26.6** 上通过 Release 真机构建。本机工具链需要包含工程使用的 SwiftUI / FoundationModels SDK；旧 Xcode 16.2 无法完整构建。运行时的 `#available` 判断不能保护编译 SDK 中不存在的符号。

直接使用已提交的 `V2EX.xcodeproj`，无需先运行 XcodeGen。只有修改 `project.yml` 并同步工程设置时才重新生成，注意保留实际版本号、构建号和源码引用。

```bash
# 模拟器
xcodebuild -project V2EX.xcodeproj -scheme V2EX \
  -destination 'generic/platform=iOS Simulator' build

# 真机（需签名配置）
open V2EX.xcodeproj   # Signing & Capabilities 里选 Team 后 Cmd+R

# 独立网络回归测试，不需要完整 iOS SDK
python3 tests/check-reply-submission.py
```

### 本地 Android 构建

使用 JDK 21（Java 源码目标 17），配置 `JAVA_HOME`、`ANDROID_HOME`；安装 Android SDK Command-line Tools 23.0、`platforms;android-37.0` 和 `build-tools;37.0.0`。Gradle 9.7.1 / AGP 9.3.2 由工程固定，无需全局安装 Gradle。

在仓库根目录执行：

```bash
./android/gradlew -p android --no-daemon :app:testDebugUnitTest :app:assembleDebug
./android/gradlew -p android --no-daemon :app:lintDebug
```

APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。也可用 Android Studio 直接打开 `android/`。Android 的源码来源和签名细节见 [`android/UPSTREAM.md`](android/UPSTREAM.md)。

## 项目结构

```text
V2EX.xcodeproj         # iOS 工程，保留原路径
V2EX/                  # iOS 源码
V2EXTests/             # iOS 单元测试
tests/                 # 独立回归检查
android/               # Android 工程、Gradle Wrapper、app 和测试
docs/                  # 原 iOS 文档与 Pages 内容
.github/workflows/     # iOS、Android 各自独立的手动构建及原有发布流程
```

### iOS 源码

```
V2EX/
├── App/              # 入口、Tab 导航、路由
├── DesignSystem/     # 主题配色、组件、话题视图
├── Features/         # 首页/节点/通知/话题/搜索/个人/设置
├── Models/           # Codable 模型
├── Networking/       # V2EX API、网页会话、HTML 解析
├── Storage/          # 设置、Keychain、收藏与离线缓存
├── V2EX.icon/        # Icon Composer 应用图标
└── IconSources/      # 图标源图层
```

## 说明

- 浏览话题无需登录；通知、个人资料和长帖分页需要 Personal Access Token
- App 内回复、收藏和关注节点同步需要 V2EX 网页会话；密码不会保存
- V2EX 没有开放发帖 API，草稿可自动保存后到网页发布
- API 频率上限 600 次/小时/IP
