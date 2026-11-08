# 技术设计

更新日期：2026-11-08。本文按当前源码静态梳理电视工坊的实现，作为架构、数据流与边界行为的参照；未运行构建、模拟器或真机验证，检查范围见[第 10 节](#10-已知限制与未验证边界)。文中代码路径均相对仓库根目录。

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
- 页面只有两个：`MainActivity`（竖屏主页，LAUNCHER）与 `PlayerActivity`（横屏播放页，`sensorLandscape` + `singleTop` + `exported=false`，仅内部跳转，见 `app/src/main/AndroidManifest.xml:20-38`）。导航用显式 Intent，未引入 Navigation 组件库。
- 旋转适配：主页竖屏固定；播放页用 `configChanges`（orientation|screenSize|screenLayout|keyboardHidden）接管，不重建。
- 网络仅 `HttpURLConnection`（订阅下载），JSON 仅 `org.json`（信号源序列化），无第三方网络 / 图片 / 序列化库。

### 版本与构建

| 项 | 值 | 出处 |
|---|---|---|
| AGP | 9.3.3（内置 Kotlin，无 kotlin-android 插件） | `gradle/libs.versions.toml:2` |
| Gradle Wrapper | 9.5.0 | `gradle/wrapper/gradle-wrapper.properties` |
| KSP | 2.3.11（仅 Room 编译器） | `app/build.gradle.kts:3`、`gradle/libs.versions.toml:53` |
| minSdk / targetSdk / compileSdk | 29 / 37 / 37 | `app/build.gradle.kts:8-15` |
| versionCode / versionName | 1 / 1.0 | `app/build.gradle.kts:16-17` |
| 关键依赖 | media3 1.8.0（exoplayer + hls + ui）、Room 2.8.4、Material 1.14.0、AppCompat 1.8.0、Lifecycle 2.10.0、Coroutines 1.10.2 | `gradle/libs.versions.toml` |
| Release | `optimization { enable = false }`，未开 R8 | `app/build.gradle.kts:22-27` |

## 2. 包结构

| 包 | 文件 | 职责 |
|---|---|---|
| `com.sugar.tv` | `TvApplication`、`MainActivity` | 应用级依赖入口；主页（分类、搜索、收藏、导入） |
| `com.sugar.tv.base` | `BaseActivity` | 统一 edge-to-edge 开关 |
| `com.sugar.tv.browse` | `BrowseModels`、`BrowseViewModel`、`ChannelListAdapter` | 主页状态契约（分类、UiState、事件）、业务状态、列表渲染 |
| `com.sugar.tv.data` | `ChannelRepository`、`SourceCodec` | 数据仓库（Room + 下载 + 导入编排）；信号源 JSON 编解码 |
| `com.sugar.tv.data.builtin` | `BuiltinChannels` | 内置频道目录（66 个频道） |
| `com.sugar.tv.data.db` | `AppDatabase`、`ChannelEntities`、`ChannelDaos` | Room 数据库、三张表实体、DAO |
| `com.sugar.tv.data.m3u` | `M3uParser` | m3u / m3u8 订阅文本解析（纯逻辑，无 IO） |
| `com.sugar.tv.data.model` | `ChannelModels` | `ChannelCategory` / `ChannelSource` / `Channel` 业务模型（均 `@Keep`） |
| `com.sugar.tv.player` | `PlayerActivity`、`PlayerViewModel`、`PlayerUiState`、`PlayerChannelsSheetFragment`、`ChannelSheetAdapter` | 横屏播放页、播放业务状态、频道抽屉 |

资源侧：布局 6 个（主页 / 播放页 / 导入对话框 / 两类列表项 / 频道抽屉），`values` 与 `values-night` 浅深色主题，`values-w600dp` 宽屏限宽，11 个语言目录的 `strings.xml`。

## 3. 关键数据流

### 3.1 浏览页（MainActivity ← BrowseViewModel ← ChannelRepository）

1. **频道全集**：`importedDao.observeAll()`（Room Flow）→ 与 `BuiltinChannels.all` 合并、导入实体解码为业务频道（JSON 损坏的条目丢弃），`flowOn(Dispatchers.Default)`（`ChannelRepository.observeAllChannels`，`app/src/main/java/com/sugar/tv/data/ChannelRepository.kt:67-70`）。导入表任何变化都会触发重新合并。
2. **快照合成**：`BrowseViewModel` 用 `combine` 把「频道全集 × 收藏 id × 最近观看 id × 收藏乐观覆盖」合成 `ChannelSnapshot`（`app/src/main/java/com/sugar/tv/browse/BrowseViewModel.kt:98-110`）。
3. **UI 状态**：快照再与「已生效搜索词 × 当前分类」`combine`，`stateIn(WhileSubscribed(5_000))`；首次发射（首数据到达）即把 `loading` 置为 `false`（`BrowseViewModel.kt:116-126`）。搜索词在写入侧 250ms 去抖，清空立即生效（`BrowseViewModel.kt:141-157`）。
4. **过滤规则**：最近观看按 `recentIds` 顺序经 `channelsById` 映射（已删除频道自动跳过）；其余分类按频道实体分类过滤；搜索词忽略大小写包含匹配（`BrowseViewModel.buildUiState`，`BrowseViewModel.kt:254-291`）。
5. **收藏乐观更新**：点击星标先写 `favoriteOverrides` 覆盖表立即生效，仓库 `toggleFavorite`（DAO 单条事务）成功后等待仓库流反映最新值（1 秒宽限超时兜底）再释放覆盖；失败静默回滚（`BrowseViewModel.toggleFavorite`，`BrowseViewModel.kt:169-188`）。
6. **渲染**：`MainActivity` 在 `repeatOnLifecycle(STARTED)` 中收集 UiState、导入进行中状态与事件通道；加载 / 空态 / 列表三态互斥，列表内容变化才 `submitList`，Chip 选中态按布尔绝对值幂等同步（`app/src/main/java/com/sugar/tv/MainActivity.kt:229-259`）。分类切换时列表回顶；Snackbar 同一时刻仅保留最新一条，快速连击不堆叠。
7. **恢复**：`SavedStateHandle` 键 `browse_selected_category`（枚举名）与 `browse_active_query`（去抖后搜索词）；进程重建时回填（`BrowseViewModel.kt:67-75`）。

### 3.2 播放页（PlayerActivity ← PlayerViewModel）

1. **进入**：主页只传 `PlayerActivity.EXTRA_CHANNEL_ID`（`MainActivity.kt:300-304`）；Intent extras 会作为初始值进入 `SavedStateHandle`，进程重建时取写入后的最新值。
2. **加载**：`loadChannel` 先取消上一次加载 Job，IO 线程查询 `channelById`，写回前核对 `latestRequestedChannelId` 代次——快速连续换频道只有最后选择生效；随后更新 UiState（`playRequestId + 1`）并在 IO 线程记录一次观看（`app/src/main/java/com/sugar/tv/player/PlayerViewModel.kt:111-150`）。
3. **装载**：`PlayerActivity.render` 发现 `playRequestId` 变化且播放器就绪时 `setMediaItem + prepare + play`；装载成功才推进 `appliedPlayRequestId`（`app/src/main/java/com/sugar/tv/player/PlayerActivity.kt:224-232,307-314`）。重试当前源是「下标不变、序号递增」，因此必须以序号而非下标判断重装。
4. **换频道 / 换源**：上 / 下频道按频道全集顺序循环回绕；手动换源循环切换并从 `allSourcesFailed` 恢复；同一频道重复加载不重新起播（`sameRequest` 判定），新频道从第一路开始，起始下标经 `resolveStartIndex` 写回恢复句柄（`PlayerViewModel.kt:280-302`）。
5. **失败轮换**：播放器 `Player.Listener.onPlayerError` → `onPlaybackError` 自动切下一路；已到最后一路则进入 `allSourcesFailed` 错误态并按有限次数自动重试（`PlayerActivity.kt:90-100`、`PlayerViewModel.kt:183-227`）。
6. **频道抽屉**：`PlayerChannelsSheetFragment` 与播放页共享 Activity 作用域 ViewModel，本地名称过滤，`ChannelSheetRow.isPlaying` 参与 Diff 使高亮行单独重绑（`app/src/main/java/com/sugar/tv/player/PlayerChannelsSheetFragment.kt:71-110`）。
7. **手势与画中画**：播放区手势（单击确认切换浮层、双击左右半区与横向轻扫换台）由 `ChannelGestureListener` 识别，触摸经 `performClick` 桥接点击监听保证无障碍一致；频道变化时中央 OSD 显示频道名 2 秒淡出（`PlayerActivity.kt:283-299,425-455`）。画中画以 16:9 进入，期间覆盖层隐藏且 `onStop` 不释放播放器（`PlayerActivity.kt:122-150`）。

## 4. Room 数据库（三张表）

数据库 `tv_workshop.db`，version 1，`exportSchema=false`；由 `ChannelRepository` 以单例持有，页面与 ViewModel 不直接访问（`app/src/main/java/com/sugar/tv/data/db/AppDatabase.kt`）。

| 表 | 实体 | 字段 | 说明 |
|---|---|---|---|
| `favorite_channels` | `FavoriteChannelEntity` | `channelId`(PK)、`addedAt` | 收藏只存频道 id；`observeFavoriteIds()` 按 `addedAt DESC`；`toggle` 为 `@Transaction` 原子切换并返回落点状态（`ChannelDaos.kt:34-42`） |
| `recent_channels` | `RecentChannelEntity` | `channelId`(PK)、`watchedAt` | 最近观看；`upsert` 覆盖式写入；`observeRecentIds()` 按 `watchedAt DESC` |
| `imported_channels` | `ImportedChannelEntity` | `id`(PK)、`name`、`groupName?`、`sourcesJson` | 导入频道；`id` 为 `m3u:` + SHA-256 前 12 位，同名同源重复导入幂等；`observeAll` 按 `rowid ASC` 稳定排序；信号源以 `SourceCodec` JSON 序列化 |

频道本体不落库：收藏 / 观看表按频道 id 弱关联到「内置目录 ∪ 导入表」，频道被清空后列表映射自动跳过，清空自定义频道不影响收藏与观看记录。

## 5. 内置频道目录

`app/src/main/java/com/sugar/tv/data/builtin/BuiltinChannels.kt` 固化 66 个频道（脚本化核对：`builtin:` 前缀 66 条；CCTV 24、SATELLITE 32、FOREIGN 10）：

- **央视 24**：CCTV-1～CCTV-17（含 CCTV-5+ 共 18 个开路频道）+ CGTN 及纪录 / 西语 / 法语 / 阿语 / 俄语 5 个分频道。
- **卫视 32**：湖南、浙江、东方、江苏、北京、深圳、广东、山东、天津、安徽、江西、湖北、河南、四川、黑龙江、辽宁、吉林、河北、山西、贵州、云南、广西、东南、海南、甘肃、宁夏、青海、新疆、西藏、内蒙古、重庆、陕西。
- **外国 10**：BBC World News、CNN International、NHK World-Japan、DW English、France 24 English、Al Jazeera English、CNA、KBS World、ABC News (Australia)、TRT World。

播放地址固化自公开发布的第三方 m3u 订阅（KDoc 注明采集于 2026-11-08）：`live.fanmingming.com` IPv6 列表（央视主源、CGTN 主源）、`iptv-org.github.io`（cn / au / kr / news 分类）、`Guovin/iptv-api` 聚合列表、`iptv-org/iptv` 韩国原始流文件。信号源标签内置为「主源 / 备用1 / 备用2」，导入源为「线路N」；频道 id 前缀 `builtin:`。频道名称属数据不参与 i18n。目录以只读 `List` / `Map`（`all` / `byId`）暴露，不落库、不参与收藏合并之外的持久化。

## 6. m3u 解析规则

`app/src/main/java/com/sugar/tv/data/m3u/M3uParser.kt` 为纯解析逻辑（无 IO，调用方在 IO / Default 调度器执行）：

- 逐行 `trim` 并去掉 BOM（`\uFEFF`）；空行与 `#` 注释行（`#EXTINF` 除外）跳过，`#EXTM3U` 头不强制存在。
- **`#EXTINF` 行**：属性按 `([a-zA-Z0-9_-]+)="([^"]*)"` 提取（`tvg-id` / `tvg-name` / `group-title`）；频道名取「首个不被双引号包裹的逗号」之后的文本，兼容名称含逗号；空名回退 `tvg-name`，两者皆无则丢弃该条。
- **URL 配对**：首个非注释非空行视为 URL；`#EXTINF` 先入待配对队列，「连续多条 EXTINF 共用一条 URL」时该 URL 分配给每条待配对项；URL 必须为 http(s)。
- **裸 URL 容错**：无 EXTINF 声明的 URL 按「无元数据条目」解析，名称取最后一个非空路径段（去扩展名，`index` 特判）否则取主机名，`group=null`。
- **分类推导**（`categorize`）：group 或名称含「央视 / CCTV / CNTV / CGTN」（忽略大小写）→ `CCTV`；含「卫视」→ `SATELLITE`；含 CJK 统一表意文字（U+4E00–U+9FFF）→ `CUSTOM`；其余 → `FOREIGN`。
- **导入 id**（`importedChannelId`）：`"m3u:" + SHA-256("$name\n$firstUrl")` 前 12 位十六进制——同名同源重复导入得到相同 id，在持久化层天然去重。
- `tvg-id` / `tvg-name` 已解析进 `M3uEntry` 但当前未使用（EPG 预留）。

## 7. 导入流程

入口为主页工具栏「导入频道源」对话框（`MainActivity.showImportDialog`，`MainActivity.kt:311-341`）：输入以 `http://` / `https://` 开头走订阅地址导入，否则按已获取的订阅正文导入（`BrowseViewModel.importChannels`，`BrowseViewModel.kt:196-224`）。进行中 `importInFlight` 拒绝重复触发，对话框禁用按钮并显示进度。

```
输入 ──http(s):// 开头──► importFromUrl ──► requireValidHttpUrl ──► downloadAndParse ─┐
     └─否则──────────────► importFromText ──► M3uParser.parse(text) ──────────────────┤
                                                                                      ▼
                              importEntries（均在 Dispatchers.IO）
                              1. 空结果 → IllegalArgumentException
                              2. 同名合并（key = 去空白 + 小写；group 取首个非空；
                                 URL 保序去重）
                              3. 与内置目录同名者剔除已固化 URL：
                                 内置目录按「空白归一化 + 小写名称 → 地址」预建索引，
                                 大体积订阅的重名过滤 O(订阅数)
                                 全部重复则跳过
                              4. 组装 ImportedChannelEntity：
                                 id = m3u:sha256(名称\n首个URL)[0..12)，
                                 sourcesJson = SourceCodec.encode(线路N × URL)
                              5. 与已导入表按 id 去重后 REPLACE 写入，
                                 返回实际新增数量
```

- **下载（流式）**（`ChannelRepository.downloadAndParse`，`ChannelRepository.kt:223-244`）：`HttpURLConnection` GET，连接与读取超时各 10 秒；非 2xx 抛 `IOException`；字符集优先响应头 `Content-Type` 的 `charset`，无法识别回退 UTF-8；响应体经 `bufferedReader` 逐行喂给 `M3uParser.parse(BufferedReader)`（流式解析，整份文本不驻留内存），`finally` 中断开连接。
- **错误归一**：`IllegalArgumentException` → 提示「地址或内容格式不正确」（`import_invalid_url`），覆盖地址非法与解析不出可播放频道两类；`IOException` → 提示「网络不可用」；`CancellationException` 原样上抛。
- **清空**：`clearImportedChannels` 删除导入表全部行，返回行数；收藏与观看记录不受影响（第 4 节的弱关联设计）。
- 导入结果与清空结果经事件通道（`BrowseEvent`）以 Snackbar 反馈；导入成功关闭对话框，失败保留输入便于重试。

## 8. 播放器生命周期与信号源轮换

**实例归属**：ExoPlayer 实例归 `PlayerActivity` 所有，ViewModel 只决定「该播放哪一路」。`onStart` 执行 `ensurePlayer()` 创建或复用；`onStop` 与 `onDestroy` 执行 `releasePlayer()`——**直播不后台播放**，离开前台即释放；例外是**画中画中仍属可见前台，`onStop` 不释放**（以 `isInPictureInPictureMode` 判定，`PlayerActivity.kt:117-150`）。释放时 `appliedPlayRequestId` 归零，回到前台由 UiState 重放自动重新装载当前信号源并重新缓冲。

**创建配置**（`PlayerActivity.ensurePlayer`，`PlayerActivity.kt:331-360`）：`USAGE_MEDIA` + `AUDIO_CONTENT_TYPE_MOVIE` 音频属性并接管音频焦点（`handleAudioFocus=true`）、`handleAudioBecomingNoisy=true`（拔耳机暂停）；**直播 LoadControl 调优**——`DefaultLoadControl` 压缩首包等待（起播 800ms / 重缓冲 1500ms，min 15s / max 30s），换台与卡顿恢复更快出画；`PlayerView use_controller=false`、`surface_type=surface_view`，全部控件自绘；根布局 `keepScreenOn=true`。

**换源平滑过渡**：换频道 / 换源 / 重试复用同一实例 `setMediaItem + prepare + play`，不重建播放器。`MediaItem` 构造时对 `.m3u8` / `.m3u` 结尾的地址显式声明 `MimeTypes.APPLICATION_M3U8`，避免带查询串等场景按扩展名推断失败；其余地址自动识别（`PlayerActivity.buildMediaItem`，`PlayerActivity.kt:261-269`）。

**轮换状态机**（`PlayerUiState`）：

| 字段 | 作用 |
|---|---|
| `playRequestId` | 播放请求序号：换频道 / 换源 / 重试时递增，UI 据此装载；下标不变仅序号递增的场景（重试）也覆盖 |
| `currentSourceIndex` | 当前信号源下标；错误时 +1 轮换，末尾后进入全部失败 |
| `allSourcesFailed` | 全部信号源按顺序尝试仍失败：显示错误浮层并停止顺序轮换，随后按有限次数自动重试（见下） |
| `channelMissing` | 频道不存在（如恢复后频道已删除）或未传入频道 id：显示错误浮层 |
| `isBuffering` | 由播放器 `STATE_BUFFERING` 回调同步，驱动中央加载指示；错误浮层显示时不叠加 |

手动换源 `nextSource` 循环切换（少于 2 路时按钮禁用）且可从「全部失败」恢复；`retry` 在频道缺失 / 无信号源时重新查询频道，否则保持下标重试当前源。**有限自动重试**（`PlayerViewModel.scheduleAutoRetry`，`PlayerViewModel.kt:209-227`）：全部失败后延迟 8 秒、仍处错误态时重新装载当前源一次，单回合最多 2 次；换频道、手动重试或换源都会取消待执行的重试并把计数归零，不给确定失效的频道无限轮询。错误浮层显示期间操作区保持常显（取消 5 秒自动隐藏）。

**清晰度选择**（`PlayerViewModel.kt:257-291`、`PlayerActivity.kt:292-380`）：播放器 `onTracksChanged` → `extractQualityOptions` 提取被支持的视频格式——按高度去重（同高度取最高码率）、高度降序，不足两档（固定单码率源）返回空列表、UI 隐藏入口；模式三档：AUTO（带宽自适应）/ HIGHEST（`setForceHighestSupportedBitrate` 锁最高不降档）/ PINNED（`setMaxVideoHeight` 锁定高度及以下的最高档），锁定的前置顶高度在新频道不存在时自动回落 AUTO；`selectQuality` 递增 `qualityRequestId`，`render` 据此把 `TrackSelectionParameters` 应用到播放器，`ensurePlayer` 新实例立即恢复当前偏好。选择对话框为单选（自动 / 最高 / 高度标签 `1080p`——纯技术标签经 `Locale.US` 格式化）。

**手势与换台 OSD**：播放区触摸交给 `ChannelGestureListener`（`PlayerActivity.kt:437-470`）——单击确认（约 300ms 去抖）经 `performClick` 切换浮层显隐（TalkBack / 键盘行为一致）、双击左 / 右半区换台、横向轻扫按速度阈值与横纵比约束换台（右扫上一台、左扫下一台）；频道变化时中央 OSD 显示频道名 2 秒淡出（`renderChannelOsd`，`PlayerActivity.kt:283-299`），进页首帧与每次换台都会展示。

**画中画**：底栏入口以 16:9 画幅进入（设备不支持时静默忽略）；进入后覆盖层整体隐藏、退出恢复并重新计时自动隐藏（`onPictureInPictureModeChanged`，`PlayerActivity.kt:138-149`）；画中画期间信号源轮换照常工作（播放器未释放）。

**窗口与交互**：系统栏图标固定深色样式（`SystemBarStyle.dark(TRANSPARENT)`，不随浅深色翻转）；顶栏 / 底栏按状态栏∪挖孔与手势条避让，消费后返回 `CONSUMED`；5 秒无操作自动隐藏（淡出 200ms，完全隐藏后 GONE 退出无障碍遍历）。

## 9. 尺寸、主题与多语言资源

- **尺寸**：`values/dimens.xml` 提供 `dp_1`~`dp_500` 与 `sp_1`~`sp_500`（脚本生成，各 500 项）及语义别名 `browse_list_max_width`（默认 500dp，`values-w600dp` 覆盖为 480dp，主页列表与浮层在 ≥600dp 宽屏居中限宽）。
- **主题**：`Theme.TvWorkshop` 基于 Material 3 DayNight NoActionBar，完整映射品牌色 / 表面色 / 错误色语义属性，`values-night/colors.xml` 提供深色方案；`PlayerTheme` 覆盖为纯黑窗口背景 + 浅色系统栏图标。系统栏在主题中透明，配合 `BaseActivity` 的 `enableEdgeToEdge`。
- **文案**：46 个 string key 同步维护在 11 个目录（`values` 简中基准 + `values-en/ar/de/es/fr/ja/ko/pt/zh-rCN/zh-rTW`）；本次核对 11 个目录 key 集合完全一致。格式化文案统一占位符（`source_count_format`、`switch_source_format`、`import_success_format`、`import_failed_format`）；`about_body` 为面向最终用户的频道来源说明（与 README 免责节、上架文案同口径）。
- **图标**：「电视工坊」自定义自适应图标——品牌蓝对角渐变背景（`ic_launcher_background`，#2E6ED6→#153A69＋右上柔光圆）＋白色「电视机身＋双天线＋播放键」前景（`ic_launcher_foreground`，图形收在中央 66dp 安全区内）；`monochrome` 层复用同一前景供 Android 13+ themed icon。minSdk 29 纯自适应实现，无密度 PNG。设计原稿（SVG）见 [project-docs/design/tv-workshop-logo.svg](design/tv-workshop-logo.svg)。
- **drawable 内的主题属性边界**：嵌套 drawable（selector / ripple 的 item）一律用直接颜色资源（如 `bg_sheet_channel_item` 的 `tv_secondary_container` / `tv_control_highlight`），不用 `?attr`——BottomSheet 的 LayoutInflater 包装路径下解析失败会崩溃（真机 Android 10 已验证，2026-11-08 修复）；根级 vector 的 `fillColor="?attr/…"` 可用。

## 10. 已知限制与未验证边界

以下边界如实记录，未运行任何构建 / 测试 / 设备验证（工程规范见 [AGENTS.md](../AGENTS.md)）：

1. **未做模拟器 / 真机验证**。代码通过编译门禁前的静态审查，但未安装运行：页面渲染、Insets 避让、横竖屏、进程重建恢复、播放链路端到端行为均未经设备确认。
2. **直播源可用性未实测**。66 个内置源固化自第三方公开列表（2026-11-08），未逐一验证可达性；直播源随时间失效，部分主源为 IPv6 地址，需要 IPv6 网络环境。
3. **明文 HTTP（已于 2026-11-08 放开）**。内置目录大量主源 / 备源与导入订阅均为 `http://` 明文流，targetSdk 28+ 平台默认禁止明文流量，真机实测会全部播放失败（`CleartextNotPermittedException`）；已配置 `android:usesCleartextTraffic="true"` 放开。权衡：导入源不可枚举、无法按域白名单收紧，且流量仅限公开流地址、不含用户凭据（见 [AGENTS.md](../AGENTS.md) 隐私约束）；后续加入登录能力时必须改用 `networkSecurityConfig` 按域收紧。
4. **预留未接线的能力**：`EmptyReason.FAVORITES` / `empty_favorites` 为收藏视图预留，当前五类 Chip 无「收藏」入口，分支不可达（`BrowseModels.kt:39-48`）；`ChannelRepository.removeImportedChannel` 与 `PlayerActivity.createIntent` 已实现但无调用方（单频道删除入口未开放，主页直接构造 Intent）。
5. **EPG 未实现**：m3u 的 `tvg-id` / `tvg-name` 已解析保存（导入表 `groupName` 同理），但无节目单功能。
6. **测试覆盖薄**：仅模板测试（`ExampleUnitTest` / `ExampleInstrumentedTest`），`M3uParser` / `SourceCodec` / `categorize` 等纯逻辑无自动化测试。
7. **导入无大小上限**：下载与解析已流式衔接（逐行消费，整份文本不驻留内存），但解析结果与合并结构仍全量持有，超大列表（数万频道）导入时的峰值内存与耗时未实测；解析为一次性全量操作，无增量与进度百分比。
8. **备份规则为模板默认**：`allowBackup=true` 且 `backup_rules.xml` / `data_extraction_rules.xml` 为未定制的模板，Room 数据随系统自动备份；如需排除需先收紧规则。

## 11. 后续可扩展项

按当前结构自然延伸的方向（均未实现，仅为设计预留说明）：

- **节目单 EPG**：`tvg-id` 已在解析层预留，可接 xmltv 源做播放页节目单。
- **信号源健康度**：对多路源做可达性 / 测速预检，按延迟排序或记住每个频道最后可用源，替代纯顺序轮换。
- **内置源画质标注**：为内置频道信号源标注清晰度 / 码率（列表或播放页展示），换源时提示画质差异；采集时优先选择高清源。
- **单频道管理入口**：开放 `removeImportedChannel` 的 UI（长按删除 / 导入表管理页）。
- **收藏分类入口**：启用预留的 `EmptyReason.FAVORITES` 分支，在分类 Chip 中加入收藏视图。
- **导入来源扩展**：经 SAF 选择本地 `.m3u` 文件导入，补齐「订阅地址 / 粘贴正文」之外的第三入口。
- **自动化测试**：为 `M3uParser.parse` / `categorize` / `importedChannelId`、`SourceCodec` 边界与 `BrowseViewModel` 状态合成补 JVM 单测。
