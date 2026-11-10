# 电视工坊

[![English](https://img.shields.io/badge/Language-English-blue?style=for-the-badge)](README.md)

## Special Thanks

特别感谢所有开源项目与第三方库的作者和维护者，也感谢身边参与测试、提出建议的朋友们。你们的时间、智慧与支持，让本项目得以不断完善 ❤️

本项目的实现离不开以下开源项目、第三方组件与内容来源的帮助：

- [fanmingming/live](https://github.com/fanmingming/live) — 部分内置电视频道列表与信号地址的参考来源，包括部分央视与 CGTN 主源。感谢作者与维护者对直播列表的整理与维护。
- [iptv-org/iptv](https://github.com/iptv-org/iptv) — 部分国内外频道地址与频道分类列表的参考来源。
- [Guovin/iptv-api](https://github.com/Guovin/iptv-api) — 直播源整理工具；部分内置地址可追溯到其历史输出文件。
- [Kotlin](https://github.com/JetBrains/kotlin) — 应用开发语言与标准库。
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) — 协程、异步任务与 Flow 状态流。
- [Material Components for Android](https://github.com/material-components/material-components-android) — Material 界面控件、主题与对话框。
- [AndroidX / Jetpack](https://developer.android.com/jetpack/androidx) — Core、Annotation、AppCompat、Lifecycle、ConstraintLayout 与 RecyclerView，提供界面、列表与页面状态管理。
- [AndroidX Media3](https://github.com/androidx/media) — ExoPlayer、HLS 与播放器 UI，提供直播媒体播放能力。
- [Room](https://developer.android.com/jetpack/androidx/releases/room) — 收藏、最近观看与导入频道的本地数据库。
- [Markwon](https://github.com/noties/Markwon) / [CommonMark Java](https://github.com/commonmark/commonmark-java) — 中文离线文档的 Markdown 解析与排版，页面结构参考拼图工坊与 RadioWorkshop。
- [JUnit 4](https://github.com/junit-team/junit4) / [AndroidX Test 与 Espresso](https://github.com/android/android-test) — JVM 单元测试与 Android 界面测试基础。
- [Gradle](https://github.com/gradle/gradle) / [Android Gradle Plugin](https://android.googlesource.com/platform/tools/base/) / [KSP](https://github.com/google/ksp) — 构建工具链与 Room 编译期符号处理。

具体列表出处、实际流服务与内容权利方的区别，见[内置频道与来源](#内置频道与来源)。

---

电视工坊是一款面向个人技术学习与研究的 Android 直播电视播放器。它在本机浏览、搜索和播放第三方直播地址，提供收藏分类、最近观看、频道管理、m3u / m3u8 文件导入与导出、自动换源、清晰度与画面比例选择、睡眠定时和画中画等功能。

当前源码版本为 `1.0`，最低支持 Android 10（API 29）。项目使用 Kotlin、Android View/XML、ViewBinding 和 MVVM。

## 项目概览

| 项目 | 当前行为 |
| --- | --- |
| 内置目录 | 66 个频道、162 个播放地址；目录随应用发布，不会启动时自动同步上游 |
| 导入方式 | 粘贴单行 HTTP(S) 订阅地址、m3u / m3u8 正文，或在频道管理页选择 UTF-8 文件；导入是一次性快照 |
| 频道管理 | 搜索全部导入频道、单个删除、导出全部导入频道列表；可单独清空观看记录 |
| 播放方式 | 支持全屏和画中画；普通退后台停止播放 |
| 观看设置 | 完整显示、等比裁切或拉伸；可设置 15～120 分钟睡眠定时 |
| 本地记录 | 保存收藏、最近观看和导入频道；系统可能参与备份或设备迁移 |
| 网络请求 | 订阅下载和播放资源由设备直接访问第三方服务，应用不做遥测或播放行为上报 |
| 随包文档 | 关于页提供离线更新日志、用户协议、隐私政策和频道来源说明 |

## 功能概览

### 浏览与导入

- 按最近观看、我的收藏、央视、卫视、外国频道和自定义分类浏览；收藏跨频道分类聚合，最新收藏在前。
- 主页搜索按频道名包含匹配，输入有 250ms 去抖，筛选在后台执行，页面重建后恢复已生效查询。
- 可以在频道条目或播放页收藏频道，播放请求会写入最近观看记录。
- 导入入口支持订阅地址或列表正文，也兼容多行裸 URL；同一批次的同名频道会合并线路并按顺序去重。
- 与内置频道同名但拥有新地址的条目另存为导入频道，重复导入已有记录不会更新其中的其他线路。导入频道按名称和分组分类，不一定出现在“自定义”。
- “清空自定义频道”会删除全部导入频道，保留收藏和最近观看记录。

### 频道与观看记录管理

从主页右上角菜单进入“频道管理”：

- 查看、搜索全部导入频道，点按频道即可播放，删除按钮经确认只删除该条导入记录。
- “从文件导入”通过系统文件选择器读取 UTF-8 编码的 `.m3u` / `.m3u8`，不申请存储权限，复用原有同名合并和去重规则。
- “导出频道列表”将全部导入频道的名称、分组和各路完整地址保存为 `.m3u`；搜索不会限制导出范围，不包含内置目录、收藏或观看记录。地址可能包含令牌，导出前会提醒妥善保管。
- 导出是频道列表快照，重新导入仍按同名合并规则处理，不是数据库完整备份；分组中的双引号会转为单引号，元数据换行转为空格。
- “清空观看记录”经确认清除全部最近观看记录，包含已删除频道留下的记录，保留收藏和导入频道。

文件位置不作为订阅保存，也不会自动刷新；如选择云文件提供器，文件读写由相应提供器处理。

### 播放与换台

- 直播进度落后时先在原线路重新接入一次，其他致命错误或恢复失败后按顺序尝试后续线路；末路失败后每隔 8 秒自动重试当前线路，最多 2 次。普通缓冲等待不会直接换源。
- 上一频道、频道列表、下一频道固定在底部；线路、画质和画中画放在设置菜单中。
- 多档视频源可选择自动、最高或高度上限；新源没有原高度时回落自动档。
- 设置菜单新增画面比例：完整显示、保持比例裁切铺满、拉伸铺满；只改变显示方式，不重新装载直播。
- 睡眠定时支持 15、30、60、90、120 分钟或关闭，剩余分钟显示在播放顶栏。换台和换源不中断计时，到期停止音视频并退出播放页；画中画同样生效。
- 画面模式和定时在当前播放页重建后恢复。计时期间使用单调时钟，进程恢复按保存的截止时间换算；已经到期时不会在回前台后重新开播。主动退出播放页结束本次设置，不提供系统级闹钟或后台播放服务。
- 双击左右半区或横向轻扫可以换台。操作区隐藏时，频道名称只在顶部边缘短暂提示，不覆盖画面中央。
- 操作区通常在 3 秒无操作后隐藏，键盘、遥控器焦点、读屏或错误提示需要操作时保持可见。
- 频道列表与视频在同一页分区：宽窗口并排，窄窗口上下排列。列表可搜索并标出当前频道，点选、关闭、返回或点击预览均可恢复全屏；页面重建后保留查询和列表位置。
- 设备支持时可以进入画中画。普通退后台不会继续播放，回到前台后按当前状态重新装载线路。

### 关于与离线文档

主页菜单中的“关于”显示当前构建版本，并提供：

- [版本更新日志](docs/version-history/CHANGELOG.md)
- [用户协议](docs/legal/USER_AGREEMENT.md)
- [隐私政策](docs/legal/PRIVACY_POLICY.md)
- 频道来源说明（详见[内置频道与来源记录](project-docs/CHANNEL_SOURCES.md)）

关于页和三份正文当前只提供简体中文，其他通用界面文案继续维护既有多语言。正文随 APK 提供，阅读无需网络，支持章节定位、阅读位置恢复和回到顶部；更新日志可按版本展开或收起，区分“开发中”与 `v1.0` 基础能力。

## 内置频道与来源

内置目录参考了 [fanmingming/live](https://github.com/fanmingming/live)、[iptv-org/iptv](https://github.com/iptv-org/iptv) 和 [Guovin/iptv-api](https://github.com/Guovin/iptv-api) 的部分公开列表或历史结果，部分地址仍缺少可复核的逐条出处。固定版本、地址比对范围、2026-11-09 内容抽样和移除的 7 个错配地址，集中记录在[内置频道与来源记录](project-docs/CHANNEL_SOURCES.md)。

公开列表的整理者、实际流服务和节目权利方可能不是同一主体。应用与这些项目或电视台没有官方合作或背书；“主源”“备用”只表示尝试顺序，不代表官方来源、画质或长期可用性。地址可能受地区、运营商、IPv6、网络变化或第三方策略影响。

应用不托管、转播或转码直播内容，也不提供录制和媒体下载功能；播放仍需接收并缓冲音视频。公开可访问和学习研究用途都不替代内容授权，软件或列表许可证也不授予节目使用权。请只导入和播放自己有权使用的内容，并遵守相应服务条款和适用法律。

发现来源错误、地址失效或权利问题时，可在 [Issues](https://github.com/SiXuManYan/TvWorkshop/issues) 提供频道名和必要信息；公开提交前请移除 URL 中的令牌。内置目录的调整需要修改源码并随应用更新发布。

## 隐私与数据边界

收藏、最近观看和导入频道由应用保存在本机 Room 数据库。主动导出时，频道名称、分组和完整地址会写入用户选择的位置；请自行管理导出文件。应用没有账号、统计、崩溃上报 SDK 或开发者云同步，不主动上报搜索词、播放记录或设备标识。

订阅下载、播放列表、媒体分片和外部网页仍会产生第三方网络请求。对方可能看到 IP、请求地址和普通请求头；用户输入 URL 中的查询参数会按原样发送，可能包含令牌。应用启用 Android 系统备份且未排除 Room 数据库，数据可能随系统设置进入云备份或设备迁移。

完整说明见[隐私政策](docs/legal/PRIVACY_POLICY.md)。导入、播放和外部链接的使用边界见[用户协议](docs/legal/USER_AGREEMENT.md)。

## 构建与工具链

用 Android Studio 打开仓库根目录并等待 Gradle 同步，或执行：

```bash
./gradlew :app:assembleDebug
```

当前工具链：

- AGP 9.5.1（内置 Kotlin）；
- Gradle Wrapper 9.6.1；
- KSP 2.3.13；
- compileSdk / targetSdk 37，minSdk 29；
- AGP 运行要求 JDK 17 及以上，Java 编译目标为 11；
- Media3 1.11.1、Room 2.8.5、Material 1.14.0、Lifecycle 2.11.0、Coroutines 1.11.0。

近期工程审查以源码和资源静态检查为主，构建、测试、Lint 和设备验证未在相应修订中运行；没有据此形成性能或兼容性实测结论。当前架构和实现见[技术设计](project-docs/TECHNICAL_DESIGN.md)，各次改动的原因与检查范围见[工程变更记录](project-docs/IMPLEMENTATION_HISTORY.md)。

## 文档导航

| 需要了解 | 阅读入口 |
| --- | --- |
| 用户如何使用、有哪些限制 | 本页、[用户协议](docs/legal/USER_AGREEMENT.md)、[隐私政策](docs/legal/PRIVACY_POLICY.md) |
| 当前架构和实现 | [技术设计](project-docs/TECHNICAL_DESIGN.md) |
| 内置频道、地址出处和清理证据 | [内置频道与来源记录](project-docs/CHANNEL_SOURCES.md) |
| 历次工程审查和界面调整 | [工程变更记录](project-docs/IMPLEMENTATION_HISTORY.md)、[版本更新日志](docs/version-history/CHANGELOG.md) |
| 长期开发规则 | [AGENTS.md](AGENTS.md) |
| 工程文档索引 | [project-docs/README.md](project-docs/README.md) |
| English entry | [README.md](README.md) |
