# 技术设计

更新日期：2026-11-09。本文按当前源码静态梳理电视工坊的实现；频道来源与许可保留 2026-11-08 的核对记录，2026-11-09 补充性能与正确性审查修正、关于页与中文离线文档、界面与操作可达性调整、实际内容抽样后的信号源清理，以及播放页精简和文档折叠动画。本轮未运行构建、测试、Lint、模拟器或真机验证；直播 HTTP / 媒体抽样范围见[第 5.4 节](#54-广告错配线路清理2026-11-09)，其他检查范围见[第 10 节](#10-已知限制与未验证边界)、[第 12 节](#12-关于与本地文档)、[UI 审查记录](#13-ui-审查与调整2026-11-09)和[播放页与折叠动画记录](#14-播放页精简与文档折叠动画2026-11-09)。文中代码路径均相对仓库根目录。

## 1. 总体架构与模块

单 `:app` 模块（`settings.gradle.kts` 仅 `include(":app")`），命名空间与应用 id 均为 `com.sugar.tv`。整体为 MVVM 分层：

```
Activity / Fragment / BottomSheet   生命周期、窗口与 Insets、导航、ViewBinding 渲染
        │  用户意图（方法调用）           │  状态（StateFlow 只读）＋一次性事件（Channel）
        ▼
ViewModel（AndroidViewModel）       不可变 UiState、业务状态机、SavedStateHandle 恢复
        │  数据访问
        ▼
ChannelRepository                   内置目录合并、Room、订阅下载、m3u 解析调度
        │
        ▼
Room 三表 + BuiltinChannels         收藏 / 最近观看 / 导入频道 + 内置频道目录
```

- `TvApplication` 以 `by lazy` 持有全局唯一 `ChannelRepository`（`app/src/main/java/com/sugar/tv/TvApplication.kt:12`）；ViewModel 经 `(application as TvApplication).channelRepository` 获取，不持有其他 Context。
- `BaseActivity` 在 `super.onCreate` 前统一 `enableEdgeToEdge()`（`app/src/main/java/com/sugar/tv/base/BaseActivity.kt:15`）。
- 页面由四个 Activity 承接：`MainActivity`（竖屏主页，LAUNCHER）、`PlayerActivity`（横屏播放，`sensorLandscape` + `singleTop`）、`AboutActivity`（关于）和 `LocalDocumentActivity`（复用三份中文文档）。除 Launcher 外均 `exported=false`，导航用显式 Intent，未引入 Navigation 组件库。
- 旋转适配：主页声明竖屏；播放页用 `configChanges`（orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden）接管配置变化。实际窗口行为仍受设备与系统策略影响。
- 网络仅 `HttpURLConnection`（订阅下载），JSON 仅 `org.json`（信号源序列化），无第三方网络 / 图片 / 序列化库。

### 版本与构建

| 项 | 值 | 出处 |
|---|---|---|
| AGP | 9.5.1（内置 Kotlin，无 kotlin-android 插件） | `gradle/libs.versions.toml` |
| Gradle Wrapper | 9.6.1 | `gradle/wrapper/gradle-wrapper.properties` |
| KSP | 2.3.13（仅 Room 编译器） | `app/build.gradle.kts:3`、`gradle/libs.versions.toml` |
| minSdk / targetSdk / compileSdk | 29 / 37 / 37 | `app/build.gradle.kts:8-15` |
| versionCode / versionName | 1 / 1.0 | `app/build.gradle.kts:16-17` |
| 关键依赖 | media3 1.11.1（exoplayer + hls + ui）、Room 2.8.5、Material 1.14.0、AppCompat 1.8.0、Lifecycle 2.11.0、Coroutines 1.11.0 | `gradle/libs.versions.toml` |
| 文档排版 | Markwon core 4.6.2，仅本地 Markdown，无网络图片插件 | `gradle/libs.versions.toml`、`LocalDocumentRepository` |
| Release | `optimization { enable = false }`，未开 R8 | `app/build.gradle.kts:22-27` |

### 2026-11-08 稳定依赖升级记录

核对来源为 [Google Maven](https://dl.google.com/dl/android/maven2/master-index.xml)、[Maven Central](https://repo.maven.apache.org/maven2/)、各构件的 `maven-metadata.xml` 与发布 POM/Gradle Module Metadata；仅采用正式稳定版。AGP 9.5.1 的[官方兼容说明](https://developer.android.com/build/releases/agp-9-5-0-release-notes)要求 Gradle ≥9.6.0、JDK ≥17，Wrapper 从 9.5.0 升至 9.6.1，并用[官方 SHA-256](https://services.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256)校验发行包。

| 组件 | 原版本 | 升级后 |
| --- | --- | --- |
| AndroidX Annotation | 1.10.0 | 1.11.0 |
| Lifecycle Runtime | 2.10.0 | 2.11.0 |
| Lifecycle ViewModel | 2.10.0 | 2.11.0 |
| Room | 2.8.4 | 2.8.5 |
| Media3 | 1.8.0 | 1.11.1 |
| Coroutines | 1.10.2 | 1.11.0 |
| AGP | 9.3.3 | 9.5.1 |
| KSP | 2.3.11 | 2.3.13 |
| Gradle Wrapper | 9.5.0 | 9.6.1 |

同步 AGENTS、双语 README 和本文当前版本表。已有构建缓存中的 Media3 成员引用辅助对照未发现缺失；缓存不代表当前源码编译，不能确认 HLS/切源/音频焦点或画中画行为。实际检查仅包含版本目录 TOML 与引用一致性、升级目标的发布元数据、AAR 的 minSdk / minCompileSdk / AGP 要求、Wrapper 校验值、当前文档版本与本地链接、历史记录保全及 `git diff --check`。本次未运行 Gradle、构建、依赖图解析、单元测试、Lint、安装或设备验收，旧构建/测试结果不覆盖本次升级；没有变更应用版本号或登记新发布。后续按需验证需覆盖 Room/KSP、HLS 与普通流、清晰度切换、自动重试、横屏/画中画和前后台恢复。

## 2. 包结构

| 包 | 文件 | 职责 |
|---|---|---|
| `com.sugar.tv` | `TvApplication`、`MainActivity` | 应用级依赖入口；主页（分类、搜索、收藏、导入） |
| `com.sugar.tv.base` | `BaseActivity` | 统一 edge-to-edge 开关 |
| `com.sugar.tv.about` | `AboutActivity`、`LocalDocumentActivity`、`LocalDocumentViewModel`、`LocalDocumentRepository`、`LocalDocumentAdapter`、`DocumentSectionAnimator`、`LocalDocument`、`DocumentUi` | 关于与中文离线文档、后台读取与排版、版本展开恢复与动画、章节导航与链接出口 |
| `com.sugar.tv.browse` | `BrowseModels`、`BrowseViewModel`、`ChannelListAdapter`、`PlaylistInputEditText` | 主页状态契约（分类、UiState、事件）、业务状态、列表渲染与导入输入框滚动协调 |
| `com.sugar.tv.data` | `ChannelRepository`、`SourceCodec`、`HttpUrl` | 数据仓库（Room + 下载 + 导入编排）；信号源 JSON 编解码；共用 HTTP(S) 地址校验 |
| `com.sugar.tv.data.builtin` | `BuiltinChannels` | 内置频道目录（66 个频道） |
| `com.sugar.tv.data.db` | `AppDatabase`、`ChannelEntities`、`ChannelDaos` | Room 数据库、三张表实体、DAO |
| `com.sugar.tv.data.m3u` | `M3uParser` | m3u / m3u8 订阅文本解析（纯逻辑，无 IO） |
| `com.sugar.tv.data.model` | `ChannelModels` | `ChannelCategory` / `ChannelSource` / `Channel` 业务模型（均 `@Keep`） |
| `com.sugar.tv.player` | `PlayerActivity`、`PlayerViewModel`、`PlayerUiState`、`PlayerChannelsSheetFragment`、`ChannelSheetAdapter` | 横屏播放页、播放业务状态、频道抽屉 |

资源侧：布局 11 个（原有主页 / 播放页 / 导入对话框 / 两类频道列表项 / 频道抽屉，加关于 / 文档页 / 概览 / 章节 / 章节 Chip），`values` 与 `values-night` 浅深色主题，`values-w600dp` 主页宽屏限宽，11 个语言目录的 `strings.xml` 与新增仅中文的 `values/strings_documents.xml`。

## 3. 关键数据流

### 3.1 浏览页（MainActivity ← BrowseViewModel ← ChannelRepository）

1. **频道全集**：`importedDao.observeAll()`（Room Flow）→ 与 `BuiltinChannels.all` 合并、导入实体解码为业务频道（JSON 损坏或没有合法 HTTP(S) 地址的条目丢弃），`flowOn(Dispatchers.Default)`（`ChannelRepository.observeAllChannels`）。导入表变化时重新合并。
2. **快照合成**：`BrowseViewModel.channelCatalog` 仅在频道全集变化时创建 id 索引，再用 `combine` 把「目录 × 收藏 id × 最近观看 id × 收藏乐观覆盖」合成 `ChannelSnapshot`，收藏与观看记录更新不重复创建目录索引。
3. **UI 状态**：快照再与「已生效搜索词 × 当前分类」`combine`，合成与过滤统一经 `flowOn(Dispatchers.Default)` 离开主线程，然后 `stateIn(WhileSubscribed(5_000))`；首数据到达即把 `loading` 置为 `false`。搜索词在写入侧 250ms 去抖，清空立即生效。
4. **过滤规则**：最近观看按 `recentIds` 顺序经 `channelsById` 映射（已删除频道自动跳过）；其余分类按频道实体分类过滤；搜索词忽略大小写包含匹配（`BrowseViewModel.buildUiState`）。
5. **收藏乐观更新**：点击优先读取同步写入的 `favoriteOverrides`，不等待 UiState 下一帧；覆盖携带点击代次。数据库写入由 Mutex 按点击顺序提交（锁不包含同步等待），成功后等待仓库流反映实际值（1 秒宽限超时兜底）。仅创建该覆盖的请求可以释放它，旧请求完成或失败不会清掉后续点击的目标态；失败静默回滚。
6. **渲染**：`MainActivity` 在 `repeatOnLifecycle(STARTED)` 中收集 UiState、导入进行中状态与事件通道；加载 / 空态 / 列表三态互斥，列表内容变化才 `submitList`。Chip 按布尔绝对值同步，程序化同步期间不回写用户意图，分类变化时揭示选中项。首次渲染不回顶，后续切分类才回顶；Snackbar 同一时刻仅保留最新一条。频道名最多两行，分类与信号数经本地化格式资源合成可换行摘要，宽度约束在收藏按钮之前。
7. **恢复**：`SavedStateHandle` 键 `browse_selected_category`（枚举名）与 `browse_active_query`（去抖后搜索词）；UiState 初始值即包含恢复值，菜单无需等待 Room 首次发射即可展开搜索。SearchView 恢复词后才绑定查询监听；Chip 不单独保存选中态。`ChannelListAdapter` 使用 `PREVENT_WHEN_EMPTY`，异步目录到达后才消费 RecyclerView 保存的滚动位置。
8. **窗口与状态区**：根容器统一处理左右系统栏 / 挖孔，浮层负责顶部避让，列表顶部 padding 随浮层每次高度变化同步。加载 / 空态放在浮层下方的可滚动区域，列表与状态区底部均避让系统栏 / IME 并保留 16dp 留白。

### 3.2 播放页（PlayerActivity ← PlayerViewModel）

1. **进入**：主页只传 `PlayerActivity.EXTRA_CHANNEL_ID`（`MainActivity.openPlayer`）；Intent extras 会作为初始值进入 `SavedStateHandle`，进程重建时取写入后的最新值。
2. **加载**：加载 Job、请求标记与重试字段统一声明在启动任务的 `init` 之前，让依赖顺序明确。`loadChannel` 取消上一加载，经仓库 IO 查询 `channelById`，写回前核对 `latestRequestedChannelId`；只有最新选择写入 UiState 与恢复句柄。观看记录失败不打断直播，频道查询失败由现有错误浮层承接。
3. **装载**：`PlayerActivity.render` 发现 `playRequestId` 变化且播放器就绪时 `setMediaItem + prepare + play`，并把序号写入 `MediaItem.mediaId`；应用请求后推进 `appliedPlayRequestId`。频道缺失或无源时清空旧媒体，避免错误态下继续播旧频道。重试当前源是「下标不变、序号递增」，因此以序号判断重装。
4. **换频道 / 换源**：上 / 下频道按全集顺序循环回绕，连续点击以最新请求频道为基准，即使上次查询还没完成也能继续前进；手动换源循环切换并从 `allSourcesFailed` 恢复；同频道重复加载不重新起播，新频道从第一路开始，起始下标经 `resolveStartIndex` 写回恢复句柄。
5. **回调与失败轮换**：缓冲、档位与错误回调携带已装载媒体的请求序号，ViewModel 拒绝与当前请求不匹配的回调；`onPlaybackError` 自动切下一路，末路失败后进入错误态并有限自动重试。
6. **频道抽屉**：`PlayerChannelsSheetFragment` 与播放页共享 Activity 作用域 ViewModel，输入只更新本地查询流；`channelSheetRows` 在 Default 调度器组合全集、查询词与播放频道，过滤、映射与列表相等性检查均在后台执行。`ChannelSheetRow.isPlaying` 参与 Diff 并设置读屏状态描述；关闭条目动画，使用 `PREVENT_WHEN_EMPTY` 保留恢复位置。抽屉打开即展开，新打开且未搜索时在列表提交后定位当前频道一次；重建不重新定位，搜索有清空入口与 IME Search 收键盘处理。提交回调核对 Binding 和当前 Adapter，销毁后不访问旧视图。抽屉同步提交并按 tag 去重，快速连点不会叠加抽屉。
7. **手势与画中画**：单击确认经 `performClick` 切换浮层、双击左右半区与横向轻扫换台；频道变化且操作区隐藏时，在顶部起始边缘提示频道名 1.5 秒再淡出。设置菜单提供 16:9 画中画，覆盖层隐藏且 `onStop` 不释放播放器；退出时显式恢复操作区并重新计时。
8. **收藏**：播放页星标统一由仓库观察流驱动，写入返回值只用于提示，不直接覆盖状态，避免旧操作完成值反写新状态；写入异常不终止播放。

## 4. Room 数据库（三张表）

数据库 `tv_workshop.db`，version 1，`exportSchema=false`；由 `ChannelRepository` 以单例持有，页面与 ViewModel 不直接访问（`app/src/main/java/com/sugar/tv/data/db/AppDatabase.kt`）。

| 表 | 实体 | 字段 | 说明 |
|---|---|---|---|
| `favorite_channels` | `FavoriteChannelEntity` | `channelId`(PK)、`addedAt` | 收藏只存频道 id；`observeFavoriteIds()` 按 `addedAt DESC`；`toggle` 为 `@Transaction` 原子切换并返回落点状态（`ChannelDaos.kt:34-42`） |
| `recent_channels` | `RecentChannelEntity` | `channelId`(PK)、`watchedAt` | 最近观看；`upsert` 覆盖式写入；`observeRecentIds()` 按 `watchedAt DESC` |
| `imported_channels` | `ImportedChannelEntity` | `id`(PK)、`name`、`groupName?`、`sourcesJson` | 导入频道；`id` 为 `m3u:` + SHA-256 前 12 位，同名同源重复导入幂等；`observeAll` 按 `rowid ASC` 稳定排序；信号源以 `SourceCodec` JSON 序列化 |

内置频道本体不落库，导入频道保存在导入表。收藏 / 观看表按频道 id 弱关联到「内置目录 ∪ 导入表」，频道被清空后列表映射自动跳过，清空导入频道不删除收藏与观看记录。

## 5. 内置频道目录

### 5.1 目录与运行方式

[`BuiltinChannels.kt`](../app/src/main/java/com/sugar/tv/data/builtin/BuiltinChannels.kt) 固化 66 个频道、162 个播放地址（静态计数：`builtin:` 前缀 66 条；CCTV 24、SATELLITE 32、FOREIGN 10）：

- **央视 24**：CCTV-1～CCTV-17（含 CCTV-5+ 共 18 个开路频道）+ CGTN 及纪录 / 西语 / 法语 / 阿语 / 俄语 5 个分频道。
- **卫视 32**：湖南、浙江、东方、江苏、北京、深圳、广东、山东、天津、安徽、江西、湖北、河南、四川、黑龙江、辽宁、吉林、河北、山西、贵州、云南、广西、东南、海南、甘肃、宁夏、青海、新疆、西藏、内蒙古、重庆、陕西。
- **外国 10**：BBC World News、CNN International、NHK World-Japan、DW English、France 24 English、Al Jazeera English、CNA、KBS World、ABC News (Australia)、TRT World。

信号源标签内置为「主源 / 备用1 / 备用2」，只表示顺序尝试，不证明官方来源、画质或可用性；导入源标签为「线路N」。频道 id 前缀 `builtin:`，名称属于数据不参与 i18n。目录以只读 `List` / `Map`（`all` / `byId`）暴露，应用启动时不下载或同步上游目录，更新需修改源码并随新版本发布。

### 5.2 上游出处与核对范围

原目录注释记载整理日期为 2026-11-08，并列出 `fanmingming/live`、`iptv-org/iptv` 与 `Guovin/iptv-api`。原仓库未保存每一路地址首次采集的文件版本；本次核对补充的是“在所列上游快照中能否找到相同地址”，不能据此证明首次采集路径或唯一出处。

| 参考项目与固定版本 | 本次文本比对结果 |
|---|---|
| [fanmingming/live](https://github.com/fanmingming/live)，[tv/m3u/ipv6.m3u @ 936a3a8](https://github.com/fanmingming/live/blob/936a3a8d11a6b65cc93d659939459dcd7553cd3b/tv/m3u/ipv6.m3u) | 对应 24 个主源：18 个 CCTV 的 IPv6 地址、6 个 CGTN 的 `https://0472.org/hls/…` 地址。文件名含 IPv6 不代表其中每条地址均为 IPv6。CCTV 地址在应用中省略了上游末尾的空 `?` |
| [iptv-org/iptv](https://github.com/iptv-org/iptv)，发布目录 `0f7cb89` 下的 [countries/cn.m3u](https://github.com/iptv-org/iptv/blob/0f7cb89701ea7fa2557bf82bfd710bdc60d74ecc/countries/cn.m3u)、[countries/au.m3u](https://github.com/iptv-org/iptv/blob/0f7cb89701ea7fa2557bf82bfd710bdc60d74ecc/countries/au.m3u)、[categories/news.m3u](https://github.com/iptv-org/iptv/blob/0f7cb89701ea7fa2557bf82bfd710bdc60d74ecc/categories/news.m3u)，以及 [streams/kr.m3u @ 2edd461](https://github.com/iptv-org/iptv/blob/2edd46179e566f0004ea88d5b4af3ea498632c87/streams/kr.m3u) | 分别命中 26、1、22、2 个内置地址，去重合计 49 个。韩国原始文件可找到两个 KBS World 地址；本次检查的发布版 `countries/kr.m3u` 未命中这两个地址 |
| [Guovin/iptv-api](https://github.com/Guovin/iptv-api)，[output/result.m3u @ 6f10e86](https://github.com/Guovin/iptv-api/blob/6f10e865173f8e73c58eb3aab78315051f336824/output/result.m3u)（2025-12-30 历史文件） | 命中 12 个地址，其中 4 个与上表已命中地址重叠，另补充 8 个。[当前 README @ 6ea026f](https://github.com/Guovin/iptv-api/blob/6ea026fd8522e3755f7197a77e21218c2f6a7514/README.md) 说明其为工具／框架，旧 `output` 链接不再更新，不能将历史结果描述为当前持续供应的直播服务 |

核对日期为 **2026-11-08**。历史比较范围为清理前的 169 个地址与上表文件的 HTTP(S) 地址行，除忽略 CCTV 上游末尾的空 `?` 外不改写非空查询参数，命中按完整地址去重。合计 **81 个地址有上表文件佐证，88 个未命中**；后者需要补齐原采集快照或逐条出处，不能仅凭相似域名／路径推定来源，也不能据未命中认定其一定失效。此次来源核对仅获取仓库资料与列表文件，未请求流媒体地址或验证节目内容；以上计数不作为后续清理后的重新归属统计。

这些固定提交链接用于复核当次文本，不是应用的自动更新接口。`fanmingming/live` 是明确的参考来源之一，但不能作为整个内置目录的唯一来源。后续更新地址应同时记录频道 id、线路标签、上游文件链接、提交号／获取日期与必要的地址改写，保留可追溯依据。

### 5.3 来源角色与许可边界

- **列表整理者与实际流服务分开说明**：上述仓库提供列表或工具，列表中的地址可以指向运营商、广播机构、CDN 或其他第三方主机。应用由设备直接请求所选地址及播放所需资源；项目级出处不能证明每个地址的实际运营方、授权状态或公益性质。本项目与这些项目及电视台无官方合作或背书关系。
- **应用行为**：本项目不托管、转播或转码直播内容，不提供录制下载功能；客户端播放仍需接收并缓冲音视频。部分地址需要 IPv6 或特定地区／运营商网络，顺序换源和有限重试不构成可用性保证。
- **上游许可**：[fanmingming/live 的 LICENSE](https://github.com/fanmingming/live/blob/936a3a8d11a6b65cc93d659939459dcd7553cd3b/LICENSE) 为 GNU GPL v3；[iptv-org/iptv 的 LICENSE](https://github.com/iptv-org/iptv/blob/2edd46179e566f0004ea88d5b4af3ea498632c87/LICENSE) 文本为 The Unlicense；[Guovin/iptv-api 当前 LICENSE](https://github.com/Guovin/iptv-api/blob/6ea026fd8522e3755f7197a77e21218c2f6a7514/LICENSE) 为 GNU AGPL v3。这里记录上游文件，复制／修改／分发其材料时需按实际使用范围核对相应版本的条件；不据此替本应用自动选定许可证或断言适用范围。
- **本仓库许可状态**：当前仓库没有独立的 `LICENSE` 文件；“学习研究用途”是项目用途说明，不能作为明确的源码许可证，后续分发时需单独确定适当的许可与第三方声明。
- **节目内容权利**：仓库许可不授予电视节目、台标或商标的使用／再分发权。公开可访问、注明来源或声明学习研究用途都不替代内容授权。引用列表不表示取得节目授权。
- **反馈与移除**：本仓库 Issues 可用于反馈来源错误、地址失效或权利异议，需提供频道名、地址与必要说明。当前没有删除单个内置频道的 UI，维护者需修改内置目录并发布更新；导入频道可通过现有清空入口删除。

### 5.4 广告错配线路清理（2026-11-09）

触发原因：用户反馈部分标为电视频道的地址实际播放电视购物，要求能够判断时移除。此次为内容抽样，单独于此前的来源文本核对和应用静态审查进行。

**抽样范围与方法**：北京时间 2026-11-09 01:15～01:28，对清理前全部 169 个地址执行有限时 HTTP / 播放列表请求；124 路返回 HLS，对其逐路尝试下载一个媒体分片并解码一帧，118 路成功、6 路因 HTTP 错误或超时未得到画面。另对可疑地址做后续时段复查，并重新请求首次记录的完整跳转目的地址取得当前分片；原清单中的旧分片已返回 404，未当作当时画面复现。媒体抽样使用临时 curl / FFmpeg 工具，不经过 Android 播放器，不属于设备播放验证；临时视频、画面和带签名的跳转查询参数不打包、不提交仓库。

确认的三类目的流均缺少对应频道台标，出现与频道不符的全屏销售内容：`/applive/102.m3u8` 带咨询热线 `400-788-3916`；`/applive/yss.m3u8` 带半价抢购热线 `400-066-2600`；`/applive/mkt.m3u8` 带订购电话 `400-689-3979`。判断结合实际解码画面与跨频道共用的购物内容，不依据路径名、主机名或单次 HTTP 成功直接下结论。下表的跳转地址仅记 host / path，省略临时签名参数，时间均为当日北京时间。

| 频道 id / 原标签 | 移除的原始地址 | 实际观察与复核依据 |
|---|---|---|
| `builtin:cctv-2` / 备用1 | `http://198.204.228.26/live/cctv2hd.m3u8` | 01:15 跳至 `69.197.181.106:88/applive/102.m3u8`；01:27 直接复核该目的地址，画面为眼部产品推销及咨询热线。后续原地址也曾返回带 CCTV-2 台标的正常节目，说明存在间歇错配 |
| `builtin:cctv-16` / 备用1 | `http://207.56.13.146:81/cdnlive/cctv16.m3u8` | 原地址 01:18 返回 `mkt` 购物内容、01:25 返回 `yss` 半价推销、01:27 返回 `102` 咨询热线内容，三次均非所标奥林匹克频道 |
| `builtin:guangdong-tv` / 备用1 | `http://63.141.230.178:82/gslb/zbdq5.m3u8?id=gdwshd` | 原地址 01:18 返回 `107.150.55.138:88/applive/102.m3u8` 的推销画面；首次跳转的 `142.54.178.42:88/applive/yss.m3u8` 在 01:27 复核为半价购物。其他两路及后续原地址可见广东台标和电视剧 |
| `builtin:shandong-tv` / 备用2 | `http://107.150.60.122/live/sdwshd.m3u8` | 01:15 返回与广东上述首次请求完全相同的 `yss` 清单，内容 SHA-256 相同；01:27 直接复核该目的地址为半价购物。后续原地址可见山东台标和访谈节目 |
| `builtin:shanxi-tv` / 备用1 | `http://107.150.60.122/live/sxwshd.m3u8` | 原地址 01:20 返回 `142.54.178.42:88/applive/yss.m3u8`，解码为半价购物热线画面；保留主源及后续原地址可见山西台标和电视剧 |
| `builtin:ningxia-tv` / 主源 | `http://107.150.60.122/live/nxws.m3u8` | 01:15 跳至 `107.150.55.138:88/applive/102.m3u8`；01:27 直接复核该目的地址为产品推销及咨询热线。后续原地址也曾返回带宁夏台标的电视剧 |
| `builtin:shaanxi-tv` / 主源 | `http://63.141.230.178:82/gslb/zbdq5.m3u8?id=snwshd` | 01:15 跳至 `204.12.234.154:88/applive/mkt.m3u8`；01:27 直接复核该目的地址为酒类订购节目。保留备用及后续原地址可见陕西台标和节目 |

**最终改动**：仅删除上述 7 个原地址，保留所有 66 个频道及其 id，目录从 169 减至 162 个地址，未新增或替换 URL。CCTV-2、CCTV-16、广东原备用2改标备用1；宁夏、陕西原备用1改标主源；其他剩余线路顺序不变。宁夏和山西现为单路，宁夏保留地址本次请求未成功，不能因改标“主源”宣称可用。本次未读取或修改用户导入表，也未加入按关键字或域名自动拦截的功能。

**判断边界与检查**：删除依据是已记录的内容错配；并非声称这些地址每次都播放广告，也不将电视台自身的正常广告时段、公益广告、夜间测试卡、地区限制、IPv6 不可达或超时一概判为广告。其余线路没有长期连续观察，抽到正确画面不代表未来始终正确或可播放。静态复核目录条数、频道身份、非空源与标签顺序，确认只删除这 7 个地址，并执行本地文档链接与 `git diff --check` 检查；未运行 Gradle 构建、测试、Lint、安装、模拟器或真机任务。

## 6. m3u 解析规则

`app/src/main/java/com/sugar/tv/data/m3u/M3uParser.kt` 为纯解析逻辑（无 IO，调用方在 IO / Default 调度器执行）：

- 逐行 `trim` 并去掉 BOM（`\uFEFF`）；空行与 `#` 注释行（`#EXTINF` 除外）跳过，`#EXTM3U` 头不强制存在。
- **`#EXTINF` 行**：属性按 `([a-zA-Z0-9_-]+)="([^"]*)"` 提取（`tvg-id` / `tvg-name` / `group-title`）；频道名取「首个不被双引号包裹的逗号」之后的文本，兼容名称含逗号；空名回退 `tvg-name`，两者皆无则丢弃该条。
- **URL 配对**：首个非注释非空行视为 URL；`#EXTINF` 先入待配对队列，「连续多条 EXTINF 共用一条 URL」时该 URL 分配给每条待配对项；URL 必须为带合法主机与端口的 HTTP(S) 地址，兼容大小写 scheme 与 IPv6，拒绝 `http:xxx`、缺少主机与越界端口。
- **裸 URL 容错**：无 EXTINF 声明的 URL 按「无元数据条目」解析，名称取最后一个非空路径段（去扩展名，`index` 特判）否则取主机名，`group=null`。
- **分类推导**（`categorize`）：group 或名称含「央视 / CCTV / CNTV / CGTN」（忽略大小写）→ `CCTV`；含「卫视」→ `SATELLITE`；含 CJK 统一表意文字（U+4E00–U+9FFF）→ `CUSTOM`；其余 → `FOREIGN`。
- **导入 id**（`importedChannelId`）：`"m3u:" + SHA-256("$name\n$firstUrl")` 前 12 位十六进制——同名同源重复导入得到相同 id，在持久化层天然去重。
- 属性正则只编译一次；id 直接把摘要前 6 字节转成固定 ASCII 小写十六进制，保持已有 id 格式，不为所有摘要字节逐个创建 Formatter。
- `tvg-id` / `tvg-name` 解析进临时 `M3uEntry`，`tvg-name` 也用于空频道名回退；二者不单独写入导入表，无 EPG 功能。

## 7. 导入流程

入口为主页工具栏「导入频道源」对话框（`MainActivity.showImportDialog`）：单行且以 `http://` / `https://` 开头（忽略大小写）走订阅地址下载，多行输入按订阅正文解析，包含多行裸 URL 的正文不会被误当成一个下载地址。进行中 `importInFlight` 拒绝重复触发，对话框禁用按钮并显示进度。

浮动标签用简短的“订阅地址或列表正文”，原有长说明独立换行。输入区显示 4～6 行并启用内部滚动，不设置正文长度上限；`PlaylistInputEditText` 仅在正文可滚动时阻止外层截走滑动，不接管点击、选择与编辑。关闭此混合输入区的自动填充与拼写建议，对话框内容在短窗口也可滚动。

```
输入 ──单行 http(s)://──► importFromUrl ──► requireValidHttpUrl ──► downloadAndParse ─┐
     └─否则──────────────► importFromText ──► M3uParser.parse(text) ──────────────────┤
                                                                                      ▼
                              importEntries（均在 Dispatchers.IO）
                              1. 空结果 → IllegalArgumentException
                              2. 同名合并（key = 去空白 + 小写；group 取首个非空；
                                 URL 用 LinkedHashSet 保序去重，避免同名大量线路的二次扫描）
                              3. 与内置目录同名者剔除已固化 URL：
                                 内置目录按「空白归一化 + 小写名称 → 地址」预建索引，
                                 大体积订阅的重名过滤 O(订阅数)
                                 全部重复则跳过
                              4. 组装 ImportedChannelEntity：
                                 id = m3u:sha256(名称\n首个URL)[0..12)，
                                 sourcesJson = SourceCodec.encode(线路N × URL)
                              5. DAO 批量 INSERT IGNORE，由数据库跳过已有 id，
                                 根据实际 rowid（跳过为 -1）统计新增数，
                                 不再预读全部旧频道的正文与 JSON
```

- **下载（流式）**（`ChannelRepository.downloadAndParse`）：`HttpURLConnection` GET，连接与读取超时各 10 秒；非 2xx 抛 `IOException`；字符集优先响应头 `Content-Type` 的 `charset`（参数名忽略大小写，允许引号与后续参数），无法识别回退 UTF-8；响应体经 `bufferedReader` 逐行喂给解析器，`finally` 中断开连接。
- **错误归一**：`IllegalArgumentException` → 格式错误（`import_invalid_url`）；`IOException` → 网络不可用；数据库写入等其余异常 → 通用失败（`import_unexpected_error`），进度结束且可以重试。`CancellationException` 原样上抛；合并循环与提交前检查取消状态。阻塞网络读取仍受连接与读取超时约束，未承诺取消能立即中断套接字。
- **清空**：`clearImportedChannels` 删除导入表全部行，返回行数；收藏与观看记录不受影响（第 4 节的弱关联设计）。
- 导入结果与清空结果经事件通道（`BrowseEvent`）以 Snackbar 反馈；成功关闭对话框，失败保留输入便于重试；用户取消或页面销毁时释放对话框 Binding，避免继续持有大体积正文。
- **订阅是一次性导入**：只保存当次解析出的频道与播放地址，不保存原订阅 URL，不定时刷新。已存在的 id 会直接跳过，因此原名称和首个新地址不变、但其他线路变化时，重复导入也不会更新已有记录；名称写法或首个新地址改变可能生成新 id。
- **合并范围与展示分类**：同名合并仅发生在本次导入批次中；与内置频道同名但有新地址时，作为独立导入频道保存，不追加到内置频道的 `sources`。导入频道按 `categorize` 进入央视／卫视／外国／自定义分类，「自定义」不等同于全部已导入频道；「清空自定义频道」实际删除全部导入记录。

## 8. 播放器生命周期与信号源轮换

**实例归属**：ExoPlayer 实例归 `PlayerActivity` 所有，ViewModel 只决定「该播放哪一路」。`onStart` 执行 `ensurePlayer()` 后显式渲染当前 UiState，不依赖收集器和创建播放器的时序；`onStop` 与 `onDestroy` 释放——**直播不后台播放**，画中画模式例外（`isInPictureInPictureMode` 为 true 时保留实例）。释放先解除 PlayerView/Surface 关联，再释放实例，并重置应用请求标记；回到前台重新装载当前源。非画中画退后台取消控件和 OSD 延迟回调，回到前台恢复操作区并重新计时。

**创建配置**（`PlayerActivity.ensurePlayer`）：`USAGE_MEDIA` + `AUDIO_CONTENT_TYPE_MOVIE` 音频属性并接管音频焦点（`handleAudioFocus=true`）、`handleAudioBecomingNoisy=true`（拔耳机暂停）；`DefaultLoadControl` 的起播／重缓冲阈值配置为 800ms／1500ms，min 15s／max 30s。这是媒体缓冲参数，不能解释为“800ms 内出画”，实际耗时和效果未实测。扩展渲染器关闭（`EXTENSION_RENDERER_MODE_OFF`），启用 `setEnableDecoderFallback(true)` 在解码器初始化失败时尝试其他可用解码器；未引入额外软件解码扩展，不能保证软解可用或避免所有黑屏。`PlayerView use_controller=false`、`surface_type=surface_view`，控件自绘；根布局 `keepScreenOn=true`。

**换源平滑过渡**：换频道 / 换源 / 重试复用同一实例 `setMediaItem + prepare + play`，不重建播放器。`MediaItem` 构造时对 `.m3u8` / `.m3u` 结尾的路径显式声明 `MimeTypes.APPLICATION_M3U8`，带查询串时也可识别；其余地址自动识别。`mediaId` 保存播放请求序号，回调与 ViewModel 当前代次核对。

**轮换状态机**（`PlayerUiState`）：

| 字段 | 作用 |
|---|---|
| `playRequestId` | 播放请求序号：换频道 / 换源 / 重试时递增，UI 据此装载；下标不变仅序号递增的场景（重试）也覆盖 |
| `currentSourceIndex` | 当前信号源下标；错误时 +1 轮换，末尾后进入全部失败 |
| `allSourcesFailed` | 当前源已是末路且播放失败：显示错误浮层并停止顺序轮换，随后按有限次数自动重试（见下）；从中间线路恢复时，前面的线路可能未被尝试 |
| `channelMissing` | 频道不存在（如恢复后频道已删除）或未传入频道 id：显示错误浮层 |
| `isBuffering` | 由播放器 `STATE_BUFFERING` 回调同步，驱动中央加载指示；错误浮层显示时不叠加 |

手动换源 `nextSource` 循环切换且可从「全部失败」恢复；设置菜单的 `selectSource` 可直接选中指定线路，校验媒体请求代次，重复选择正常线路不重装。无源时不显示计数，只有一路时菜单中的线路入口禁用，错误区只在至少两路时显示一步换源按钮。`retry` 在频道缺失／无信号源时重新查询频道，否则保持下标重试当前源。自动轮换由 `onPlayerError` 触发，只尝试当前下标之后的线路，不保证恢复到中间线路时再从第一路遍历。**有限自动重试**（`PlayerViewModel.scheduleAutoRetry`）：末路失败后延迟 8 秒、仍处错误态时重新装载当前源一次，最多 2 次；换频道、手动重试或有效换源都会取消待执行的重试，其中换频道和手动重试会归零计数，当前手动换源保留计数。收藏等其他操作不取消重试。错误态取消自动隐藏并保持操作区可见，单击画面不再隐藏错误态操作；错误内容在上下操作条之间滚动，按钮纵向排列，最大宽度 400dp。

**清晰度选择**（`PlayerViewModel.onVideoQualityOptionsChanged`、`PlayerActivity.applyQualityParameters`）：`onTracksChanged` 按每条轨道的 `isTrackSupported` 过滤视频格式，按高度去重（同高度取最高码率）并降序；保留单档信息用于校验偏好，少于两档时设置菜单不提供画质项。换源时先清空旧选项，忽略装载过程的 `Tracks.EMPTY`，等真实轨道到达后再验证高度。模式三档：AUTO（带宽自适应）／HIGHEST（优先最高码率）／PINNED（高度上限内自适应）。所选高度在新选项中不存在时回落 AUTO，同时递增 `qualityRequestId` 让播放器解除旧限制；用户选择同样递增序号。打开菜单后媒体请求、画质选项或偏好变化会关闭旧菜单，回调执行前仍核对代次，ViewModel 也拒绝无效高度。新播放器立即恢复当前偏好，高度标签如 `1080p` 经 `Locale.US` 格式化。

**手势与换台 OSD**：播放区触摸交给 `ChannelGestureListener`：单击确认（约 300ms 去抖）经 `performClick` 切换浮层显隐（TalkBack / 键盘行为一致）、双击左 / 右半区换台、横向轻扫按速度阈值与横纵比约束换台（右扫上一台、左扫下一台）。频道变化且操作区隐藏时，在顶部起始边缘显示自适应宽度、最大 320dp 的频道提示，1.5 秒后淡出；已有标题或进入画中画时不重复展示，中央画面不叠加频道名称。

**画中画**：播放设置入口以 16:9 画幅进入（设备不支持时不提供菜单项，调用仍检查能力）；进入后关闭设置与子菜单、整体隐藏覆盖层，退出时显式显示顶栏与底栏，再开始自动隐藏计时；期间信号源轮换照常工作（播放器未释放）。

**窗口与交互**：系统栏图标固定为亮色（`SystemBarStyle.dark(TRANSPARENT)`），前台以 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 沉浸隐藏；返回窗口或退出画中画后恢复该行为。顶底栏、错误区与换台提示按忽略可见性的系统栏∪挖孔计算稳定安全区，系统栏临时滑出不移动按钮，Insets 消费后返回 `CONSUMED`。顶底栏使用边缘黑色到透明的渐隐遮罩，不压暗中央。底部常用入口固定为上一频道 / 频道列表 / 下一频道，设置固定末端；线路、画质、画中画通过深色弹出菜单按需提供，当前值与已选项同时可见，整页没有横向操作滚动。文字按钮随字号增高并允许省略，保留完整读屏描述，图标保留 48dp 触达区与白色焦点描边。通常 3 秒无操作隐藏（淡出并向边缘轻移 8dp，共 200ms，完全隐藏后 INVISIBLE 保持几何布局并退出焦点 / 无障碍遍历）；错误态、设置打开、持续触摸、按钮持有键盘 / 遥控器焦点或读屏触摸浏览时不自动隐藏，窗口失焦暂停。用户操作和焦点 / 窗口变化重算倒计时，任务执行前再次核对条件，不轮询；系统关闭动画时直接同步显隐。方向键可唤出隐藏操作条；显式隐藏把按钮焦点还给视频。无频道时禁用收藏，收藏描述带频道名。

## 9. 尺寸、主题与多语言资源

- **尺寸**：`values/dimens.xml` 提供 `dp_1`~`dp_500` 与 `sp_1`~`sp_500`（脚本生成，各 500 项）及语义别名 `browse_list_max_width`（默认 500dp，`values-w600dp` 覆盖为 480dp，主页列表与浮层在 ≥600dp 宽屏居中限宽）。
- **主题**：`Theme.TvWorkshop` 基于 Material 3 DayNight NoActionBar，完整映射品牌色 / 表面色 / 错误色语义属性，`values-night/colors.xml` 提供深色方案；`PlayerTheme` 覆盖为纯黑窗口背景 + 浅色系统栏图标。系统栏在主题中透明，配合 `BaseActivity` 的 `enableEdgeToEdge`。
- **文案**：55 个通用 string key 同步维护在 11 个目录（`values` 简中基准 + `values-en/ar/de/es/fr/ja/ko/pt/zh-rCN/zh-rTW`）；首次 UI 调整新增 6 个标签、摘要与操作描述 key，本次播放页新增设置与线路选项共 2 个 key。格式化文案统一占位符；`import_unexpected_error` 承接非格式、非网络的导入异常；`about_body` 仍由关于页来源说明展示。按用户本轮明确要求，关于与三份离线文档新增文案独立放在 `values/strings_documents.xml`，标记 `translatable=false`，所有语言环境统一读简体中文。详细来源以第 5 节为准。仓库未提供可对照的上架说明，不能宣称已与商店文案核对一致。
- **图标**：「电视工坊」自定义自适应图标——品牌蓝对角渐变背景（`ic_launcher_background`，#2E6ED6→#153A69＋右上柔光圆）＋白色「电视机身＋双天线＋播放键」前景（`ic_launcher_foreground`，图形收在中央 66dp 安全区内）；`monochrome` 层复用同一前景供 Android 13+ themed icon。minSdk 29 纯自适应实现，无密度 PNG。设计原稿（SVG）见 [project-docs/design/tv-workshop-logo.svg](design/tv-workshop-logo.svg)。
- **drawable 内的主题属性边界**：嵌套 drawable（selector / ripple 的 item）一律用直接颜色资源（如 `bg_sheet_channel_item` 的 `tv_secondary_container` / `tv_control_highlight`），不用 `?attr`——BottomSheet 的 LayoutInflater 包装路径下解析失败会崩溃（真机 Android 10 已验证，2026-11-08 修复）；根级 vector 的 `fillColor="?attr/…"` 可用。

## 10. 已知限制与未验证边界

2026-11-09 本轮执行源码静态审查、文档和资源静态检查，并按用户补充要求执行直播 HTTP / 媒体内容抽样；2026-11-08 的上游 README／LICENSE 与频道列表核对保留在第 5 节。本轮未运行 Gradle 构建／测试／Lint、安装或设备验证（工程规范见 [AGENTS.md](../AGENTS.md)）。历史说明中的设备结论不作为本轮通过证据：

1. **本次未做模拟器／真机验证**。页面渲染、Insets 避让、横竖屏、进程重建恢复、播放链路端到端行为均未在本次确认，也未执行编译门禁。
2. **来源记录、内容抽样与可用性分别核对**。当前 66 个内置频道含 162 个地址；2026-11-08 原 169 路中的 81 个来源文本命中与 88 个待补是清理前历史记录。2026-11-09 对 169 路做 HTTP / 清单检查，118 路成功抽取画面，结合跳转目的地复核移除 7 路购物内容错配，详见[第 5.4 节](#54-广告错配线路清理2026-11-09)。抽样不保证其余线路长期正确，未验证 Android 端播放；部分主源为 IPv6，需要相应网络环境。
3. **允许明文 HTTP**。当前 Manifest 配置 `android:usesCleartextTraffic="true"`，用于兼容 HTTP 订阅与直播地址；传输可能被观察或篡改，公开可访问不能证明内容或传输安全。导入 URL 及其参数按输入用于请求，未实现凭据剥离，不能保证用户输入不含令牌等信息。后续加入登录能力时需按 [AGENTS.md](../AGENTS.md) 收紧网络边界；本次未复验历史明文流量报错记录。
4. **预留未接线的能力**：`EmptyReason.FAVORITES` / `empty_favorites` 为收藏视图预留，当前五类 Chip 无「收藏」入口，分支不可达（`BrowseModels.kt:39-48`）；`ChannelRepository.removeImportedChannel` 与 `PlayerActivity.createIntent` 已实现但无调用方（单频道删除入口未开放，主页直接构造 Intent）。
5. **EPG 未实现**：`tvg-id`／`tvg-name` 只在解析中间对象中存在，不单独持久化；导入表保留 `groupName`，但没有节目单功能。
6. **测试尚未执行**：已增加 `M3uParserTest` 的 7 个 JVM 回归用例、`SourceCodecTest` 的 2 个 Android 用例与 `ImportedChannelDaoTest` 的 1 个 Android 用例；本轮均未运行。ViewModel 时序、清晰度回落、收藏连点与生命周期恢复仍需后续自动化或设备覆盖。
7. **导入无大小上限**：下载与解析已流式衔接（逐行消费，整份文本不驻留内存），但解析结果与合并结构仍全量持有，超大列表（数万频道）导入时的峰值内存与耗时未实测；解析为一次性全量操作，无增量与进度百分比。
8. **备份与第三方请求**：`allowBackup=true` 且备份规则未排除 Room；数据可能按设备／系统设置进入云备份或设备迁移。应用未接入开发者数据收集服务，但订阅／流服务会接收到 IP、请求地址等网络信息，不能用“仅本机保存”概括全部数据流。

### 2026-11-09 性能与正确性审查记录

触发原因：检查当前项目的性能与其他缺陷，并修正静态可确认的问题。此审查阶段未调整依赖、Room schema、UI 框架或直播地址；后续广告错配地址清理单独记在第 5.4 节。

| 问题与触发条件 | 最终修正 |
|---|---|
| 初始化依赖不清：加载任务启动在相关字段声明之前 | 字段统一置于 init 之前；已有编译缓存省略默认 null/0 赋值，因此本轮不将其列为已确认的首次播放故障 |
| 查询完成前连续换台，多个点击仍从已播放频道出发 | 按最新请求频道计算下一个目标，取消过期加载 |
| 新频道缺失但旧视频仍在播放；旧媒体回调污染尚未装载的新请求 | 缺失态递增请求并清空媒体，媒体 id 与业务请求代次核对 |
| 高度偏好因过渡空轨道被误清；回落 AUTO 只更新文案；同组不支持的格式进入选项 | 跳过过渡空轨道，逐轨道过滤并保留单档信息，回落也递增参数序号 |
| 大目录搜索、抽屉过滤与收藏状态合成在主线程；收藏变化重复创建频道索引 | 合成移到 Default，目录索引只随全集更新，抽屉先去重再渲染 |
| 收藏连点读取旧 UiState，旧任务释放新覆盖；播放页旧写入返回值反写状态 | 覆盖同步取值与代次保护、写入按序；播放页星标只认仓库流 |
| 大订阅同名多源去重反复线性扫描；导入前读取所有旧实体；并发计数可能失真 | 地址集合保序去重，INSERT IGNORE 返回真实新增数；正则和摘要编码减少重复分配 |
| 多行裸 URL 被当订阅地址；URL 只校验 scheme；charset 带后续参数时解码回退 | 单行地址／正文分流，共用完整地址校验，兼容 HTTP charset 参数格式 |
| 查询恢复先于 Room 发射时搜索框未展开；关闭导入窗口仍持有正文；画中画退出控件仍隐藏 | 初始状态包含恢复值、监听在回填后绑定、关闭即清 Binding、退出显式恢复控件 |

验证范围：静态核对调用、取消和状态流路径，读取已有 PlayerViewModel 字节码核对默认值赋值，并检查缓存 Media3 1.11.1 的所用 API 成员；运行 `git diff --check`、XML 解析、11 语言 key/格式占位符一致性与本地文档链接检查。缓存不代表当前差异已经编译通过。新增测试仅完成代码编写，未运行 Gradle、测试、Lint 或设备任务；没有帧率、内存、起播速度或源可用性的实测结论。待验收重点为首次播放、快速换台、缺失频道、限高回落与单档源、收藏连点、大目录筛选、多行 URL 导入、已有 id 重复导入、后台/画中画返回及搜索恢复。

## 11. 后续可扩展项

按当前结构自然延伸的方向（均未实现，仅为设计预留说明）：

- **节目单 EPG**：`tvg-id` 已在解析层预留，可接 xmltv 源做播放页节目单。
- **信号源健康度**：对多路源做可达性 / 测速预检，按延迟排序或记住每个频道最后可用源，替代纯顺序轮换。
- **内置源画质标注**：为内置频道信号源标注清晰度 / 码率（列表或播放页展示），换源时提示画质差异；采集时优先选择高清源。
- **来源记录补全**：为尚未核对的地址补齐可复核的上游文件与版本，并逐条核对实际节目；后续更新保留频道 id、线路、出处、获取日期和改写说明。
- **单频道管理入口**：开放 `removeImportedChannel` 的 UI（长按删除 / 导入表管理页）。
- **收藏分类入口**：启用预留的 `EmptyReason.FAVORITES` 分支，在分类 Chip 中加入收藏视图。
- **导入来源扩展**：经 SAF 选择本地 `.m3u` 文件导入，补齐「订阅地址 / 粘贴正文」之外的第三入口。
- **自动化测试**：执行现有解析、Codec 与导入 DAO 回归用例，继续补充分类推导、ViewModel 状态合成、收藏竞争与播放器生命周期覆盖。

## 12. 关于与本地文档

### 12.1 入口与文档来源

2026-11-09 根据用户需求增加版本更新日志、用户协议和隐私政策，当前仅提供简体中文。复用拼图工坊与 RadioWorkshop 的“关于信息卡 → 本地 Markdown → 章节胶囊 / 版本卡片”结构，适配电视工坊现有 Kotlin / XML / ViewBinding；没有引入其基础框架模块、Navigation、WebView 或其他业务功能。

主页溢出菜单“关于”通过显式 Intent 打开 `AboutActivity`；原有来源与版权说明保留为该页“频道来源说明”。三个文档入口复用 `LocalDocumentActivity`，新增的两个 Activity 均 `exported=false`。关于页版本来自启用的 `BuildConfig.VERSION_NAME` / `VERSION_CODE`，不在界面资源中另存版本号。应用版本仍为 `1.0` / `1`，本轮没有登记新发布。

| 文档 | 单一维护源 | 页面行为 |
|---|---|---|
| 版本更新日志 | [docs/version-history/CHANGELOG.md](../docs/version-history/CHANGELOG.md) | 按二级标题分版本，默认展开第一项；各卡片独立展开，章节定位会先展开目标 |
| 用户协议 | [docs/legal/USER_AGREEMENT.md](../docs/legal/USER_AGREEMENT.md) | 条款完整展开，章节胶囊定位；说明来源、内容权利与使用边界 |
| 隐私政策 | [docs/legal/PRIVACY_POLICY.md](../docs/legal/PRIVACY_POLICY.md) | 条款完整展开，说明本地记录、第三方请求、HTTP / URL 令牌、系统备份与清理范围 |

`app/build.gradle.kts` 将根目录 `docs/` 挂为 assets 源目录，APK 与仓库查阅同一份正文；README、AGENTS 和 `project-docs/` 不因此进入 assets。三份文档可离线阅读，不下载线上版本。日志将“开发中”与 1.0 基础能力分开，不虚构旧版本发布日期或将静态修正写成实测性能收益。

### 12.2 读取、渲染与恢复

- `LocalDocument` 枚举统一稳定标识、中文标题、固定 asset 路径和折叠规则；未知 Intent 参数回退到用户协议，不直接拼接文件路径。
- `LocalDocumentRepository` 在 IO 读取 UTF-8 Markdown，在 Default 按 `#` 一级标题、概述和 `##` 章节拆分并生成不可变 Span。`###` 等子标题保留在正文，不使用 details 容器，也不在围栏代码块中写二级标题。当前文档较小，各章 Span 在一次加载中准备；正文 View 按可见范围回收，并未实现超长日志的逐版本懒排版。
- 使用与参考项目相同的 Markwon core 4.6.2，不启用网络图片插件。后台渲染器由 Mutex 串行访问，重试先取消旧任务，取消异常原样上抛。UI 使用单独渲染器只执行 `setParsedMarkdown` 的 TextView 绑定钩子，补齐列表缩进等字号相关测量；不在主线程重新解析 Markdown。
- `LocalDocumentViewModel` 暴露不可变 UiState + StateFlow，管理加载、失败重试和展开集合。只向 `SavedStateHandle` 保存文档标识与展开下标 IntArray；正文不进入 Bundle。配置变化复用已读内容，进程重建从随包资产重载正文并恢复有效展开下标；显式保存空集合以保留“全部收起”。
- `LocalDocumentAdapter` 使用稳定章节身份、ListAdapter / DiffUtil，关闭整行条目动画；展开变化通过 payload 交给 Holder 的 `DocumentSectionAnimator`，复用参考项目的 240ms 高度、透明度与箭头联动。异步 Diff 期间保留最新提交目标，连续反向操作先取消旧监听，再从当前视觉进度接续；首次绑定、恢复、离屏和系统关闭动画时直接完成。收起立即退出正文焦点与读屏遍历，结束再清空正文和链接；高度归位为 `wrap_content`，宽度变化直接结束旧测量动画。定位等待目标列表绑定、全部高度动画结束和最终布局完成；离屏、回收与销毁主动结束动画。`PREVENT_WHEN_EMPTY` 防止加载中的空列表提前消费 RecyclerView 阅读位置恢复。
- Activity 在 STARTED 期间收集，章节导航只随内容变化创建；正文滚动高亮当前章节，胶囊可横向自动揭示。回顶定位概览，正文支持选择。页面销毁解除列表和对话框引用，文档数据与 ViewModel 不持有 Activity 或点击回调。

### 12.3 窗口、语言与链接

关于页与阅读页沿用 DayNight 品牌配色，内容最大宽度为 500dp，窄窗口随可用宽度收缩。根容器以系统栏、挖孔与 IME 的并集设置绝对 padding，消费后子 View 不再重复避让；工具栏与章节条固定，正文独立滚动。标题、摘要不设固定行高，入口和胶囊保留至少 48dp 触达范围；返回与前进图标支持 RTL 自动镜像。

按本轮明确语言要求，新增 24 个文案集中放在 `values/strings_documents.xml` 并标记 `translatable=false`，其他语言环境同样显示这些中文入口和正文；通用 `strings.xml` 当前的 55 个 key 在 11 个目录保持一致。原来源说明继续使用既有多语言资源。

协议互链仅接受登记的 `USER_AGREEMENT.md` / `PRIVACY_POLICY.md` 文件名，在内部页面打开。外链只接受 HTTP(S)，先展示目标地址确认，再通过系统 ACTION_VIEW + CATEGORY_BROWSABLE 打开；缺少浏览器时提示，页面不崩溃。Span 点击回调在绑定时创建，后台结果不持有页面。应用不新增网络权限、账号、自动反馈或首次启动同意门禁。

### 12.4 实际检查与待验收

本轮执行静态源码审查、`git diff --check`、62 个 XML（含 Manifest）的解析与资源引用检查、通用 11 语言 key / 格式占位符一致性检查、24 个中文专用资源标记检查、三份正文结构与 65 处本地文档链接 / 锚点检查，核对 Manifest 内部页面声明及 docs 的 assets 配置。另读取缓存 Markwon 4.6.2 源码核对 URLSpan 与 TextView 绑定钩子；缓存 API 核对不代表当前依赖解析或编译通过。

未运行 Gradle 构建、测试、Lint、安装、模拟器或真机任务，未验证 APK 实际打包结果或页面外观。后续验收需覆盖三入口往返、离线正文、连续展开 / 收起、章节定位与高亮、回顶、重建后的滚动与展开恢复、浅深色、大字体、RTL、窄宽窗口、系统栏 / IME，以及外链确认和无浏览器提示。没有基于本轮设备实测的兼容性或性能结论。

## 13. UI 审查与调整（2026-11-09）

本节保留该轮调整时的行为和检查范围；播放页后续精简与文档动画的当前实现见[第 14 节](#14-播放页精简与文档折叠动画2026-11-09)。

触发原因：用户要求指出并修正现有界面可优化之处。静态阅读主页、播放页、导入框、频道抽屉和新增文档布局后，优先处理文字可读性、窄窗口操作可达性与页面恢复；沿用现有品牌色、XML / ViewBinding 和 MVVM。

| 问题与触发条件 | 最终调整 |
|---|---|
| 播放底栏所有入口排一行，长翻译或大字体可能挤出屏幕 | 上下频道固定两端，中部入口横向滚动；文字按钮不固定高度，图标触达区保持 48dp |
| 自动隐藏不区分触摸、键盘 / 遥控器焦点和读屏，操作中可能失去按钮 | 按生命周期、触摸、窗口焦点、按钮焦点与触摸浏览状态控制计时，隐藏任务再核对；方向键可唤出控件，无常驻轮询 |
| 错误内容按整屏居中，短横屏或大字体下可能与操作条重叠 | 在上下操作条之间提供可滚动、限宽错误卡片；按钮纵向排列，只显示可执行的换源操作 |
| 无源仍显示 1/0、未加载可点收藏、不支持的画中画入口点后无反馈 | 无源隐藏计数，无频道禁用收藏，单路换源弱化并禁用；不支持画中画时隐藏入口 |
| 主页空态按整屏居中，未考虑浮层与键盘，浮层高度仅首帧同步 | 状态区在浮层下方可滚动并避让 IME，左右安全区统一处理，浮层每次高度变化同步列表留白 |
| 初次状态渲染强制回顶，空适配器提前消费恢复状态 | 首次不回顶，异步列表使用 PREVENT_WHEN_EMPTY，后续切分类才回顶；选中 Chip 自动揭示 |
| 频道名只显示一行，分类与源数量缺少右边界，大字体可能与收藏按钮重叠 | 名称最多两行，摘要占用有边界的宽度并可换行，收藏描述带频道名 |
| 导入长说明作为浮动标签，正文粘贴后无限撑高 | 短标签与说明分开，输入显示 4～6 行并内部滚动，正文长度不受 UI 行数限制；内容区可滚动 |
| 频道抽屉可能停在折叠高度，无快捷清空和当前位置 | 打开即展开，首次列表提交后定位播放项，重建保留位置；增加清空搜索、收起 IME 和播放项读屏描述 |
| 列表、播放与文档入口缺少明确焦点，关于页三入口分界较弱 | 统一圆角涟漪与填色 / 描边焦点，播放器固定白色反馈，关于页入口增加浅深色分隔线 |

本轮同步双语 README、工程索引与用户更新日志，通用资源从 47 增至 53 个 key，11 语言 key 与占位符保持一致；关于与正文继续仅中文，应用版本保持 1.0。静态检查通过：65 个 XML 的解析、资源引用、ViewBinding 成员对应、布局尺寸与嵌套 drawable 颜色约束，53 个通用 key × 11 语言的占位符与 24 个中文专用文案标记，8 份 Markdown 中 69 处本地链接 / 锚点，以及 `git diff --check`。另静态核对缓存 Android 编辑控件与 Lint 触摸检查逻辑，未执行 Lint。本次未运行构建、测试、Lint、安装、模拟器或真机验证。

待验收重点：浅深色、大字体与长翻译、RTL、窄宽 / 短窗口、横屏侧边挖孔及三键导航、IME、导入长正文滚动、遥控器 / 键盘 / TalkBack、列表重建后的滚动恢复、频道抽屉搜索与定位，以及前后台 / 画中画返回。以上为代码与资源调整，不作为设备外观、无障碍表现或性能实测的通过结论。

## 14. 播放页精简与文档折叠动画（2026-11-09）

触发原因：用户要求参考常见播放器简化操作、减少观看遮挡，并把参考项目的文档展开 / 收起动画迁入电视工坊。本次继续使用 Kotlin / XML / ViewBinding 与原有 MVVM，不新增依赖、播放业务或数据表。

### 14.1 播放页

- 用顶部和底部的渐隐遮罩替换整块半透明栏，中央不新增常驻控件。常用换台三入口固定在底部，频道列表增加文字标识；线路、画质与画中画集中到深色设置菜单，避免横向寻找入口。
- 菜单线路可直接点选，显示当前线路计数与画质模式，子菜单标记已选项。媒体代次、选项或偏好变化关闭旧菜单，选择动作执行前再次核对；ViewModel 的 `selectSource` 校验代次与下标，重复选择正常线路不重装媒体。单路线路禁用，多档画质与画中画按能力提供；错误卡片仍保留直接重试和一步换源。
- 非画中画前台隐藏系统栏，边缘滑动可临时唤出；以忽略可见性的 Insets 保持按钮与提示安全区稳定。退后台、进入画中画或菜单失效时同时关闭根菜单和独立子菜单，避免 AppCompat 窄窗口子菜单残留。
- 自动隐藏调整为 3 秒，顶底栏 200ms 淡出并轻移，最后设为 `INVISIBLE`。错误态不接受隐藏操作；读屏、按钮焦点、持续触摸和设置打开均暂停自动隐藏，生命周期和窗口恢复继续沿用现有保护。
- 换台名称只在控件隐藏时出现在顶部起始边缘，1.5 秒后淡出；控件出现立即清理提示。首次显示和控件可见时使用标题反馈，避免在中央重复覆盖画面。

### 14.2 文档动画

参考 `RadioWorkshop/app/src/main/java/com/sugar/radio/ui/settings/VersionHistoryController.kt` 与拼图工坊的同类实现，迁入相同的 240ms `fast_out_slow_in` 过渡；只移植必要逻辑，按本项目回收列表适配，不引入参考工程的框架模块。

`DocumentSectionAnimator` 归每个章节 Holder，单个 ValueAnimator 同步正文高度、透明度和箭头 180° 旋转，收起完成前继续参与布局，让后续卡片连续上移。取消时先解除监听，反向从当前高度、透明度和角度接续；首屏 / 恢复不播放入场动画，系统关闭动画时直接完成。收起立即撤出正文焦点和无障碍遍历，结束后清理链接，完成后恢复 `wrap_content` 以适应窗口和字号；宽度变化、离屏、回收与页面销毁结束到目标态。法律章节仍始终展开。

展开 payload 不重做 Markdown 解析，也不叠加 RecyclerView 的整行变更动画。章节定位在 Diff 提交、可见行绑定、全部高度动画结束后等待最终布局，再执行滚动；连续导航只使用最新待定位章节。进程重建仍由原来的 SavedStateHandle 展开集合和 RecyclerView 阅读位置恢复承接。

### 14.3 检查边界

本轮静态源码审查覆盖显隐和菜单清理路径、媒体请求代次保护、连续动画取消与回收、章节定位时序。检查通过：68 个 XML 解析与 344 处资源引用、4 份 Kotlin 文件的 ViewBinding 成员对应、布局尺寸和嵌套 drawable 颜色约束、55 个通用 key × 11 语言的占位符与 24 个中文专用文案标记、8 份 Markdown 中 79 处本地链接 / 锚点和三份随包正文结构，以及 `git diff --check`。另阅读缓存 AppCompat 1.8.0 的菜单实现和深色主题资源，核对根菜单与窄窗口独立子菜单的关闭边界。静态核对不等同于编译或运行通过。

按用户要求未使用真机或模拟器；遵循项目默认约定，未运行 Gradle 构建、测试或 Lint。待验收范围：窄宽 / 短窗口、大字体与长翻译、RTL、挖孔与手势 / 三键导航、浅深色、键盘 / 遥控器 / TalkBack、菜单打开期间自动换源、画中画 / 前后台往返，以及文档连续反向、离屏回收、章节导航、系统关闭动画和重建恢复。没有本轮设备外观、动画帧率或直播可用性的实测结论。
