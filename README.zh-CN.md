# 电视工坊

[![English](https://img.shields.io/badge/Language-English-blue?style=for-the-badge)](README.md)

### Special Thanks

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

具体列表出处、实际流服务与内容权利方的区别，见[内置频道与直播源说明](#内置频道与直播源说明)。

---

一款用于个人技术学习与研究的 Android 直播电视播放器：在本机浏览、搜索并播放第三方直播地址，支持收藏、最近观看、m3u / m3u8 频道列表导入与信号源自动轮换。

当前源码版本：`1.0`。要求 Android 10（API 29）及以上。基于 Kotlin、Android View 体系 / XML、ViewBinding 与 MVVM 构建。

## 功能

| 功能 | 说明 |
|---|---|
| 频道分类浏览 | 最近观看 / 央视（含 CGTN）/ 卫视 / 外国频道 / 自定义五类，横向 Chip 单选切换 |
| 内置频道目录 | 随应用内置 66 个频道（央视 24、卫视 32、外国 10）、169 个播放地址；「主源 / 备用」表示尝试顺序 |
| 收藏 | 列表条目星标与播放页收藏按钮，写入本地 Room；列表乐观状态按点击代次确认，播放页星标跟随数据库观察流 |
| 最近观看 | 播放即记录，按最近时间倒序展示；清空自定义频道不影响收藏与观看记录 |
| 搜索 | 主页工具栏展开式搜索，按频道名包含匹配，输入 250ms 去抖；目录筛选在后台执行，重建页面时恢复已生效查询 |
| 导入 m3u / m3u8 频道列表 | 支持单行订阅地址（导入时下载）或直接粘贴列表正文（含多行裸 URL）；同一批次同名频道合并信号源并保序去重，数据库原子跳过已有 id，提示实际新增数；逐行下载解析，解析结果仍全量保存在内存中；不定时刷新订阅 |
| 信号源自动轮换 | 播放报错后自动切下一路信号源，到末路失败后显示错误浮层，并每隔 8 秒重试当前源、最多 2 次；手动重试、换源或换台会取消待执行重试 |
| 清晰度选择 | 多档视频源提供「自动 / 最高 / 高度上限」切换，单档源隐藏入口；新源不含所选高度时回落自动并解除原限制，装载时的临时空轨道不清除偏好；启用备用解码器尝试，实际解码能力由设备决定 |
| 关于、更新日志与协议 | 主页菜单「关于」进入独立信息页，展示当前构建版本，提供中文离线版本更新日志、用户协议、隐私政策；支持章节定位、滚动高亮、回顶与日志折叠，并保留频道来源说明 |
| 横屏播放页 | 横屏全屏播放器：顶栏（返回 / 频道名 / 收藏）、底栏（上 / 下频道 / 频道列表 / 换源 / 清晰度 / 画中画）、双击左右半区或横向轻扫换台＋换台 OSD、5 秒无操作自动隐藏、频道抽屉带名称过滤；起播缓冲阈值配置为 800ms，实际出画时间取决于网络、源和设备；画中画可继续播放，其他后台场景释放播放器 |

2026-11-09 完成一轮静态性能与正确性审查，修正快速换台重复目标、清晰度限制未解除、收藏竞争、主线程全量过滤与导入去重等问题。后台/画中画返回时恢复操作区，缺失频道会清空旧视频。新增回归测试代码，但本轮未运行构建、测试、Lint 或设备验证，没有实测性能结论，详见[审查记录](project-docs/TECHNICAL_DESIGN.md#2026-11-09-性能与正确性审查记录)。

## 版本与协议

从主页右上角菜单进入「关于」，即可查看[版本更新日志](docs/version-history/CHANGELOG.md)、[用户协议](docs/legal/USER_AGREEMENT.md)和[隐私政策](docs/legal/PRIVACY_POLICY.md)。三份正文随 APK 提供，阅读无需网络；本轮按需求仅提供简体中文，其他应用文案继续保持既有多语言支持。日志区分“开发中”与 1.0 基础能力，应用版本号未变更，也未补造发布日期。

阅读页沿用拼图工坊与 RadioWorkshop 的章节胶囊、版本卡片和离线文档结构，适配本项目 XML / ViewBinding。法律文档始终完整展示，版本卡片可独立展开或收起，页面重建恢复阅读位置和展开状态。协议互链在应用内打开；网页链接经确认后交系统浏览器，不使用 WebView。

## 内置频道与直播源说明

### 来源与致谢

感谢 [fanmingming/live](https://github.com/fanmingming/live)、[iptv-org/iptv](https://github.com/iptv-org/iptv) 等项目提供的频道列表整理与相关工具。本项目的内置目录参考了这些公开列表，并在源码 [BuiltinChannels.kt](app/src/main/java/com/sugar/tv/data/builtin/BuiltinChannels.kt) 中保存所选频道及播放地址。

| 参考项目 | 当前能够核对的范围 |
|---|---|
| [fanmingming/live](https://github.com/fanmingming/live) | [tv/m3u/ipv6.m3u](https://github.com/fanmingming/live/blob/936a3a8d11a6b65cc93d659939459dcd7553cd3b/tv/m3u/ipv6.m3u) 对应 18 个 CCTV 主源及 6 个 CGTN 主源；CCTV 地址为 IPv6，CGTN 地址为 HTTPS 域名 |
| [iptv-org/iptv](https://github.com/iptv-org/iptv) | 中国、澳大利亚、新闻分类列表及韩国原始流文件中可核对到部分央视／卫视地址、CGTN 备源与外国频道地址 |
| [Guovin/iptv-api](https://github.com/Guovin/iptv-api) | 原目录注释列出的另一参考项目，部分地址可在[历史输出文件](https://github.com/Guovin/iptv-api/blob/6f10e865173f8e73c58eb3aab78315051f336824/output/result.m3u) 中找到；该项目当前定位为工具框架，旧输出链接已停止更新 |

核对日期为 **2026-11-08**。当前 169 个地址中，81 个可在本次检查的上游文件中找到（CCTV 主源比较时忽略上游末尾的空 `?`）；其余 88 个尚缺可复核的逐条来源记录。因此不能将全部内置地址归为 `fanmingming/live`，也不能宣称每一路来源都已确认。文件版本与比对范围见[技术设计 · 内置频道目录](project-docs/TECHNICAL_DESIGN.md#5-内置频道目录)。列表文本匹配不代表直播可用性或内容授权已经验证。

### 播放与更新方式

内置目录随应用版本发布，应用启动时不会自动订阅或同步上述仓库。播放时由设备直接请求所选地址及其播放列表、媒体分片等资源；列表整理项目与实际流服务提供方可能不同，本项目与上述项目及各电视台无官方合作或背书关系。

通过「导入频道源」可加入自己有权使用的订阅地址或列表正文。导入保存的是当次解析出的频道和地址，不会定期更新，也不保存原订阅地址作为后续刷新入口。同一批次的同名条目会合并；与内置频道同名但含新地址的条目会作为独立导入频道保存，不会替换内置目录。导入频道按名称与分组分类，可能出现在「央视」「卫视」「外国频道」中，并非全部进入「自定义」分类。

### 使用边界与内容权利

- **可用性不保证**：第三方地址可能失效、限地区、限运营商或要求 IPv6 网络；「主源 / 备用」仅是尝试顺序，不表示官方来源、画质或可用性认证。
- **内容权利归相应权利方**：应用提供客户端播放功能，不托管、转播或转码直播内容，也不提供录制下载功能；播放过程中仍需接收并缓冲音视频。地址能公开访问，不等于获得内容使用或再分发授权。
- **学习用途不替代授权**：本项目用于个人技术学习与研究，请使用已获授权的内容并遵守相关服务条款。上游仓库许可证与电视节目、商标等内容权利分别适用；引用列表不代表取得内容授权，详见[上游许可说明](project-docs/TECHNICAL_DESIGN.md#53-来源角色与许可边界)。
- **来源异议反馈**：如发现误标、失效或涉及权利异议的地址，请通过本仓库 Issues 提供频道名、对应地址及必要说明，供维护者核对和处理。当前应用无删除单个内置频道的界面入口，维护者需修改内置目录并发布更新。

## 隐私

- 收藏、观看记录与导入频道在本机 Room 数据库中处理和保存；应用未配置开发者数据接收服务，无账号体系、无统计 / 崩溃上报 SDK，不主动上报这些记录。
- Manifest 仅申请 `INTERNET` 权限。网络访问用于下载用户提供的订阅，以及请求播放列表、媒体分片和播放所需的相关资源；相应服务提供方会接收到 IP 地址、请求地址等网络请求信息，日志政策由对方决定。
- 当前开启 Android 系统备份（`allowBackup=true`），且未排除 Room 数据库；根据设备、系统设置和备份服务，本机数据可能进入系统云备份或设备迁移，不能承诺数据始终不离开设备。

完整说明见随包提供的[隐私政策](docs/legal/PRIVACY_POLICY.md)，应用内入口为「关于 → 隐私政策」。其中说明 HTTP 与带令牌地址的请求、清理导入频道的真实范围，以及系统备份和主动反馈的处理边界。

## 构建

用 Android Studio 打开仓库根目录，等待 Gradle 同步完成即可运行；或命令行执行：

```bash
./gradlew :app:assembleDebug
```

工具链：AGP 9.5.1（内置 Kotlin，无需 kotlin-android 插件）、Gradle Wrapper 9.6.1、KSP 2.3.13；compileSdk / targetSdk 37，minSdk 29。无需额外签名配置即可构建 Debug 包。

## 技术栈

- **UI**：Android View 体系 + XML + ViewBinding + Material 3（DayNight 浅深色），单 `:app` 模块；不使用 Compose。
- **架构**：MVVM——Activity / Fragment 只做生命周期、Insets、导航与渲染；业务状态为不可变 UiState + StateFlow；数据访问收敛在 `ChannelRepository`。
- **数据**：Room 2.8.5（收藏 / 最近观看 / 导入频道三张表，KSP 编译器）；`LocalDocumentRepository` 负责随包文档读取与后台排版。
- **文档阅读**：Markwon core 4.6.2，不启用网络图片插件；`docs/` 随包，研发文档 `project-docs/` 不随包。
- **播放**：Media3 / ExoPlayer 1.11.1（含 HLS 扩展），播放器实例归播放页生命周期所有。
- **异步**：kotlinx.coroutines（IO / Default 调度器分离，StateFlow + Channel）。

## 文档导航

| 问题 | 入口 |
|---|---|
| 架构、数据流、Room 表结构与 m3u 解析规则 | [技术设计](project-docs/TECHNICAL_DESIGN.md) |
| 应用内更新说明与使用、隐私规则 | [版本更新日志](docs/version-history/CHANGELOG.md)、[用户协议](docs/legal/USER_AGREEMENT.md)、[隐私政策](docs/legal/PRIVACY_POLICY.md)（当前仅中文） |
| 开发与审查规范（MVVM 边界、i18n、播放器边界等长期规则） | [AGENTS.md](AGENTS.md) |
| 工程文档索引 | [project-docs/README.md](project-docs/README.md) |
| English entry | [README.md](README.md) |
