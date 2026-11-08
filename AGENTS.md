# 电视工坊 — 项目约定

全局约定见 `~/.codex/AGENTS.md`。本文件是本仓库开发、重构和代码审查的长期规则，统一维护为根目录 `AGENTS.md`，不另建 `agent.md` 或其他大小写副本。

核心方向：**Kotlin / XML / ViewBinding + MVVM、edge-to-edge 沉浸式界面、直播播放与信号源自动轮换、本机处理与隐私克制、Markdown 文档随功能维护**。

## 工作原则与阅读入口

- 用户在当前任务中的明确要求优先；常规实现选择自行完成，不反复询问是否继续。
- 修改前先读目标页面、对应数据层与相关专题文档，保留无关未提交修改；围绕问题完成最小连贯改动，不附带迁移 UI 框架、替换存储技术或扩充产品范围。
- 用户体验包括页面之间的一致性，以及详情往返、前后台切换、进程重建恢复与窗口变化；不能只处理首次进入页面的静态效果。
- 规则与当前行为以本文件和专题的现行章节为准；历史结论只适用于各自日期/修订，不自动成为当前待办或通过证据。

| 任务 | 优先查阅 |
|---|---|
| 架构、数据流与播放器设计 | [技术设计](project-docs/TECHNICAL_DESIGN.md) |
| 功能说明与免责边界 | [中文说明](README.zh-CN.md)、[English README](README.md) |
| 内置地址出处与许可边界 | [技术设计 · 内置频道目录](project-docs/TECHNICAL_DESIGN.md#5-内置频道目录)：参考文件、固定版本与待补来源记录 |
| 文档组织与维护范围 | [工程索引](project-docs/README.md) |

## 技术栈边界

Kotlin + **Android View 体系 + ViewBinding**，不是 Compose。**不要向 Compose 迁移**，也不要引入任何 Compose 依赖；涉及 XML 布局的改动继续用 XML 完成。

- 单 `:app` 模块（`settings.gradle.kts` 仅 `include(":app")`），命名空间与应用 id 均为 `com.sugar.tv`。本项目没有 `core_framework` / `module_image` 等库模块，不要按其他项目模板引入。
- AGP **9.5.1** 内置 Kotlin（根与模块脚本均未声明 `kotlin-android` 插件，不要补加），KSP **2.3.13**（仅用于 Room 编译器），Gradle Wrapper 9.6.1；minSdk 29，targetSdk / compileSdk 37，`compileOptions` Java 11。
- 关键依赖以 `gradle/libs.versions.toml` 为准：media3（ExoPlayer + HLS + UI）1.11.1，Room 2.8.5，Material 1.14.0，AppCompat 1.8.0，Lifecycle 2.11.0，Coroutines 1.11.0；Annotation 1.11.0、Core 1.19.1。升级时同步本文和当前实现文档，默认采用稳定版；历史验证保留原日期与版本。AGP 9.5 要求 Gradle ≥9.6.0、JDK ≥17。
- 页面导航用显式 `Intent`（主页 → 播放页 / 关于页 → 本地文档页），未引入 Navigation 组件库；不要为既有页面引入导航框架或 Jetpack Compose Navigation。
- 本地文档复用 Markwon core **4.6.2**（与拼图工坊 / RadioWorkshop 一致），仅渲染随包 Markdown，不启用网络图片插件；`docs/` 打包为 assets，`project-docs/` 仍只用于研发。
- 网络只允许 `HttpURLConnection`（订阅下载），JSON 只允许 `org.json`（信号源序列化）；不引入 OkHttp / Retrofit / Gson / Coil / Glide 等第三方网络、图片或序列化库。
- Release 构建当前 `optimization { enable = false }`，未开启 R8 混淆；改动混淆配置前先确认 `@Keep` 边界仍然成立。

## Kotlin 与代码注释

- Kotlin 多参数构造和函数调用优先命名参数；语义已清楚的简短调用不强行套用。
- 新增或修改的类、函数和成员变量补充简短中文 KDoc，说明用途、约束、归属或边界；源码自然语言注释默认中文，不机械翻译代码。
- 主构造参数统一在类 KDoc 中用 `@param` / `@property` 说明；重要的取消、恢复、Insets 和播放器释放逻辑保留必要的行内解释。
- 状态读改写优先 `MutableStateFlow.update`；协程取消异常（`CancellationException`）必须原样上抛，不得被通用 `catch (Exception)` 吞掉后误报成功或失败（参照 `BrowseViewModel` / `PlayerViewModel` 的既有写法）。

## 尺寸资源

`app/src/main/res/values/dimens.xml` 提供 `dp_1`~`dp_500` 与 `sp_1`~`sp_500`（脚本生成，勿手工增删单项），另有语义别名 `browse_list_max_width`（默认 500dp，`values-w600dp` 覆盖为 480dp）。

XML 布局中的正整数尺寸一律引用 `@dimen/dp_N` / `@dimen/sp_N`，不硬编码 `10dp` / `10sp`。例外：约束布局的 `0dp`（match constraints）、资源引用本身、`match_parent` / `wrap_content` 等系统约定值。

Kotlin 取像素值用 `resources.getDimension(R.dimen.dp_N)`（浮点）或 `getDimensionPixelSize(...)`（取整），参照 `MainActivity` 的 Insets 计算写法。

## 文案与 i18n

界面文案禁止字符串模板拼接后直接 `setText`（触发 Lint `SetTextI18n` 且无法本地化语序）。统一用占位符字符串资源＋位置参数，如 `source_count_format`（`%1$d 路信号`）、`switch_source_format`（`信号源 %1$d/%2$d`）、`import_success_format`（`成功导入 %1$d 个频道`）。

- 新增或修改文案 key 必须同步补全全部 **11 个语言目录**：`values`（简中基准）+ `values-en/ar/de/es/fr/ja/ko/pt/zh-rCN/zh-rTW`，当前每个目录 53 个 key；提交前用 key 集合 diff 自查。
- **2026-11-09 用户明确指定的例外**：关于页、版本更新日志、用户协议、隐私政策本轮仅提供简体中文；新增文案集中在 `values/strings_documents.xml` 并标记 `translatable=false`，正文在 `docs/`。通用 key 继续保持 11 目录一致，不复制中文到各语言目录假作翻译。
- 频道名称（如「CCTV-1 综合」「BBC World News」）属于**数据而非界面文案**，保持官方命名，不参与多语言，不放入 strings.xml。
- 无文案词的纯数字 / 十六进制格式化注意 Locale（如 `M3uParser.importedChannelId` 的 `%02x`）；不用 `@Suppress` / `tools:ignore` 压制 `SetTextI18n`。

## 主题与 drawable 资源

- **嵌套 drawable（selector / ripple / layer-list 的 item 内）禁止 `?attr` 主题引用，一律用直接颜色资源（`@color/tv_*`）**：这类属性在 BottomSheetDialog 等 LayoutInflater 包装路径下解析失败会直接崩溃（真机已验证，Android 10），且编译期不可见。根级 vector 的 `fillColor="?attr/…"` 走 AppCompat 主题包裹路径，可用。
- 新增需浅深色区分的颜色在 `values/colors.xml` 与 `values-night/colors.xml` 成对维护；drawable 引用颜色资源而不是写死十六进制。

## MVVM 边界

Activity / Fragment 只承担生命周期、窗口与 Insets、导航、对话框 / BottomSheet 和 ViewBinding 渲染；业务状态归 ViewModel，数据访问归 Repository。

| 层 | 负责 | 边界 |
|---|---|---|
| Activity / Fragment（`MainActivity`、`PlayerActivity`、`PlayerChannelsSheetFragment`、`AboutActivity`、`LocalDocumentActivity`） | 生命周期、窗口与 Insets、菜单 / 对话框 / 抽屉、导航、控件渲染与播放器实例托管 | 不直接查询 Room、不下载订阅、不解析 m3u、不管理业务状态机 |
| ViewModel（`BrowseViewModel`、`PlayerViewModel`、`LocalDocumentViewModel`） | 不可变 UiState + StateFlow 只读暴露、用户意图、一次性事件（Channel）、`SavedStateHandle` 恢复 | 不持有 Activity / Fragment / View / Binding / Dialog；`AndroidViewModel` 只取 Application 级依赖 |
| Repository（`ChannelRepository`、`LocalDocumentRepository`） | Room 读写、订阅下载、m3u 解析调度、内置目录合并、收藏 / 观看记录；离线文档 IO 与后台 Markdown 排版 | 不依赖页面 View 生命周期，不返回 View 类型；耗时操作收敛在 IO / Default 调度器 |
| Adapter（`ChannelListAdapter`、`ChannelSheetAdapter`） | `ListAdapter` + DiffUtil 渲染，监听器绑定一次、数据幂等回填 | 不持有业务状态；点击经回调交给页面，最终转交 ViewModel |

- 页面状态用不可变 `XxxUiState`（`BrowseUiState` / `PlayerUiState`）经 `StateFlow` 只读暴露；一次性提示（导入结果、收藏提示）用 `Channel`，统一在 `repeatOnLifecycle(STARTED)` 期间收集。
- `SavedStateHandle` 只放频道 id、信号源下标、枚举名、查询词等小型可序列化数据；Intent extras 键与恢复句柄键复用（`PlayerActivity.EXTRA_CHANNEL_ID` / `EXTRA_SOURCE_INDEX`）。
- 渲染保持幂等：列表提交前比较 `currentList`，Chip 选中态按布尔绝对值同步；异步结果写回前核对代次（播放页 `playRequestId`、`latestRequestedChannelId`），旧结果不得覆盖最新选择。
- 对话框 / Binding 由页面持有并在 `onDestroy` / `onDestroyView` 主动解除，不放进 ViewModel。
- **Activity / Fragment 字段初始化器只做无上下文赋值**：构造期 `baseContext` 尚未挂载，构造内部会读 `Context` / `Resources` 的对象（如 `GestureDetector`）或任何 `getResources` / `getString` 调用都会在实例化阶段崩溃且编译期不可见；此类成员一律 `lateinit` 并在 `onCreate`（Fragment 为 `onViewCreated`）中创建。
- ViewModel 在 `init` 启动任务前先声明并初始化任务引用、请求代次与任务读取的状态字段，让初始化依赖清楚；不要依赖编译器省略默认值赋值的行为。

## Edge-to-edge 约定

`BaseActivity` 统一在 `super.onCreate` 前调用 `enableEdgeToEdge()`；系统栏在主题中透明，内容延伸到系统栏后方，操作区按 Insets 避让。

| 页面 | 外观 | Insets 责任 |
|---|---|---|
| 主页 | 列表铺满全屏，`clipToPadding=false`；「Toolbar + 分类 Chip」为半透明浮层，背景 alpha 230/255；加载 / 空态在浮层下方可滚动 | 根容器避让左右系统栏∪挖孔；浮层 padding 顶 = 状态栏∪挖孔；列表顶部 padding 随浮层实测高度变化同步；列表与状态区底部 padding = max(导航栏, IME) + 16dp 留白 |
| 播放页 | 纯黑背景，固定深色系统栏样式（`SystemBarStyle.dark`，不随浅深色翻转）；顶栏 / 底栏半透明遮罩 `#80000000`；错误内容在操作条之间可滚动 | 顶栏避让状态栏∪挖孔、底栏避让手势条，错误内容避让左右系统栏∪挖孔；以 XML 初始值为基准、重复分发不累加，消费后返回 `CONSUMED` |
| 关于 / 本地文档页 | DayNight 信息卡与阅读卡片，内容最大宽度 500dp；文档章节导航固定在顶部 | 根容器按系统栏∪挖孔∪IME 设置绝对 padding，消费后返回 `CONSUMED`；列表与正文不再重复避让 |

- 避让量按绝对值设置，不重复累加同一段系统栏高度；浮层只调背景透明度，不降低控件 alpha。
- 覆盖内容的浮层保证对比度：主页浮层用 `colorSurface`，播放页控件用白色前景＋半透明黑遮罩。
- 播放页操作区 5 秒无操作自动隐藏（淡出 200ms）；错误浮层显示、持续触摸、键盘 / 遥控器焦点停在按钮上或读屏触摸浏览启用时不自动隐藏，窗口失焦暂停计时。完全隐藏后退出 TalkBack 遍历，方向键可唤出，恢复时还原。
- 播放页上下频道入口固定在底栏两端，中部操作横向滚动；图标触达区至少 48dp，文字按钮允许随字号增高。无信号源时隐藏计数，单路弱化并禁用底栏换源，错误区仅在至少两路时显示换源。

## 播放器边界

- **ExoPlayer 实例归 `PlayerActivity` 所有**：`onStart` 创建或复用（`ensurePlayer`），`onStop` 与 `onDestroy` 释放（`releasePlayer`）——**直播不后台播放**，离开前台即释放，回到前台由状态重放重新装载当前信号源（`appliedPlayRequestId` 重置为未装载标记 -1 后随 UiState 重放）。例外：**画中画中仍属可见前台，不释放播放器**（`onStop` 以 `isInPictureInPictureMode` 判定）。
- ViewModel 只决定「该播放哪一路」：`PlayerUiState.playRequestId` 递增驱动 UI 装载；同一实例 `setMediaItem + prepare + play` 平滑换源换频道，不重建播放器。
- **直播缓冲配置**：`DefaultLoadControl` 起播阈值 800ms / 重缓冲 1500ms，min 15s / max 30s（`PlayerActivity.ensurePlayer`）；这些是媒体缓冲参数，不作为实际出画耗时或性能提升的实测结论。
- **信号源自动轮换**：`Player.Listener.onPlayerError` 交 ViewModel 按下标顺序切下一路；末路失败进入 `allSourcesFailed` 错误态停止轮换，并做**有限自动重试**（延迟 8 秒、最多 2 次，重试当前源）。换频道、手动重试或换源取消待执行重试；当前只有换频道与手动重试归零计数，换源保留计数。手动换源（`nextSource`）循环切换且可从错误态恢复。
- **播放区手势与换台 OSD**：单击确认（约 300ms 去抖）切换浮层显隐；双击左 / 右半区、横向轻扫（速度阈值 + 横纵比约束）按方向换台；频道变化时中央 OSD 显示频道名 2 秒淡出。触摸经手势识别后以 `performClick` 驱动点击监听，保证 TalkBack / 键盘激活与触摸行为一致。
- **画中画**：播放页 `supportsPictureInPicture`，底栏入口以 16:9 画幅进入；进入后覆盖层整体隐藏、退出恢复并重新计时自动隐藏；设备不支持时隐藏入口，调用仍做能力检查，不打断前台播放。
- 旋转由 Manifest `configChanges`（orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden）接管，播放页 `sensorLandscape` + `singleTop` + `exported=false` + `supportsPictureInPicture`，不重建、不重复初始化。
- m3u / m3u8 地址构造 `MediaItem` 时显式声明 `MimeTypes.APPLICATION_M3U8`，避免带查询串时按扩展名推断失败；其余地址交由 ExoPlayer 自动识别。
- **清晰度选择**：`onTracksChanged` 逐轨道过滤支持的视频格式（按高度去重、同高度取最高码率；保留单档信息，不足两种高度时隐藏入口），跳过装载时临时的空轨道；模式为 自动（带宽自适应）／最高（`setForceHighestSupportedBitrate`）／高度上限（`setMaxVideoSize` 限高，仍可在上限内自适应），所选高度在真实新选项中不存在时回落自动档；用户切换与自动回落均递增 `qualityRequestId` 驱动参数应用，`ensurePlayer` 新实例立即恢复当前偏好。
- 播放器配置：`USAGE_MEDIA` 音频属性 + `handleAudioFocus=true` + `handleAudioBecomingNoisy=true`；关闭扩展渲染器（`EXTENSION_RENDERER_MODE_OFF`），启用备用解码器尝试（`setEnableDecoderFallback(true)`）；备用解码器是否可用取决于设备，不承诺软解兜底或一定无黑屏。`PlayerView use_controller=false`、`surface_type=surface_view`，控件全部自绘；`keepScreenOn=true`。
- 换频道走全集顺序 `prevChannel` / `nextChannel`（循环回绕）；同一频道重复加载不重新起播，新频道从第一路信号源开始；起始下标经 `resolveStartIndex` 写回恢复句柄。

## 性能与协程约定

- 网络、Room、m3u 解析与文件访问放 IO 调度器（Repository 内 `withContext(Dispatchers.IO)` / `flowOn`）；频道合并、状态合成等 CPU 轻量操作放 Default；主线程只做 View 操作与轻量渲染，不用 `runBlocking`。
- 搜索输入在写入侧 250ms 去抖（`BrowseViewModel.onQueryChanged`），清空立即生效；浏览页状态流 `WhileSubscribed(5000)`，短暂跳播放页不打断上游，5 秒无订阅自动停采。
- 订阅导入流式衔接：下载逐行喂给 `M3uParser.parse(BufferedReader)`，不把整份订阅文本驻留内存；与内置目录的重名比对按「空白归一化 + 小写名称 → 地址」预建索引，大体积订阅的重名过滤由 O(订阅数 × 内置数) 降为 O(订阅数)（`ChannelRepository.importEntries`）。
- 浏览页列表调优：列表自身尺寸不依赖条目内容，启用 `setHasFixedSize`（不要求各条目等高）；离屏缓存加大到 4 条；关闭变更动画（仅保留增删 / 移动动画）；后续分类切换才回顶，首次渲染保留恢复位置，异步目录使用 `PREVENT_WHEN_EMPTY`；Snackbar 同一时刻仅保留最新一条；收藏星点击带触感反馈（`CONTEXT_CLICK`）。频道名最多两行，分类与信号数摘要可换行，避免挤占收藏触达区。
- 播放页快速连续换频道：以上次请求目标为基准继续换台，先取消上一次加载 Job，结果写回前再核对「是否仍是最新请求」，观看记录跟随核对后的结果落库。媒体回调携带 `MediaItem.mediaId` 中的请求序号并核对当前代次。
- 收藏写入收敛为 DAO 单条事务（`FavoriteChannelDao.toggle`）；浏览页按点击顺序写入，乐观覆盖同步取值且仅由相同代次释放（1 秒宽限），播放页星标统一由仓库流驱动。
- 浏览页状态合成与抽屉过滤在 Default 调度器执行，频道 id 索引只在全集变化时重建。导入地址用集合保序去重，DAO `INSERT IGNORE` 原子跳过已有 id 并据实际 rowid 计数，不先加载旧频道正文。
- 列表用稳定业务身份（频道 id）+ `ListAdapter` / DiffUtil 增量更新；相等结果不重复提交；未变化的渲染不触发无谓重绘。

## 数据模型与 @Keep

- 网络 / 序列化 / 反射读写相关的模型统一加 `androidx.annotation.Keep`：`Channel`、`ChannelSource`、`ChannelCategory`、`SourceCodec`、`M3uEntry` 与 Room 实体（`FavoriteChannelEntity`、`RecentChannelEntity`、`ImportedChannelEntity`）。
- 信号源持久化格式固定为 `SourceCodec` 的 `{"label","url"}` JSON 数组；修改字段名或格式必须保持向后兼容（旧库数据可解），解码容错跳过坏元素。
- Room schema 当前 version 1 且 `exportSchema=false`；改表结构必须同步评估迁移策略，不得静默丢数据。
- `@Keep` 只保护模型结构，不代表增加网络能力或替代隐私披露。

## m3u 导入约定

导入入口只有主页工具栏「导入频道源」对话框：粘贴订阅地址（单行 `http(s)://` 地址，前缀不区分大小写）或直接粘贴订阅正文（含多行裸 URL）。规则详见[技术设计](project-docs/TECHNICAL_DESIGN.md)，改动时保持：

- 输入说明独立换行，浮动标签保持简短；输入区显示 4～6 行并可滚动，不截断正文，对话框内容也支持短窗口滚动。
- 解析容错：兼容 BOM 与 `\r\n`；`#EXTINF` 读取 `tvg-id` / `tvg-name` / `group-title`，频道名取首个不被引号包裹的逗号之后内容；连续多条 EXTINF 共用一条 URL；裸 URL 行按无元数据条目解析。
- 合并与去重：本次导入批次内的同名频道（去空白 + 忽略大小写）合并信号源并按序去重；与内置频道同名的条目剔除已固化地址，全部重复则跳过，有新地址则保存为独立导入频道；导入 id = `m3u:` + SHA-256(名称\n首个新 URL) 前 12 位。已有 id 直接跳过，不会更新其其他线路；名称写法或首个新地址变化可能新增记录。
- 导入保存当次解析结果，不保存订阅 URL，不定时同步；导入频道按名称与分组分类，不保证全部位于「自定义」分类。「清空自定义频道」入口实际清空全部导入记录。
- 分类推导：group 或名称含「央视/CCTV/CNTV/CGTN」→ 央视；含「卫视」→ 卫视；含 CJK → 自定义；其余 → 外国频道。
- 错误归一：地址非法或无可播放频道 → `IllegalArgumentException`（提示格式错误）；下载失败 → `IOException`（提示网络不可用）；其余导入异常显示通用失败提示，取消异常原样上抛。HTTP(S) 地址须含合法主机与端口；下载超时各 10 秒、非 2xx 抛错；字符集优先响应头，回退 UTF-8。
- 清空自定义频道不影响收藏与观看记录（收藏 / 观看表按频道 id 弱关联，频道消失时列表自动跳过）。

## 隐私约束

- **本机处理**：收藏、观看记录与导入频道保存在本机 Room 库；应用无账号体系、无统计 / 崩溃 SDK、未配置开发者数据收集服务，不主动上报这些记录。`allowBackup=true` 且备份规则未排除 Room，数据可能进入系统云备份或设备迁移，文档不得承诺数据始终不离开设备。
- **订阅与播放请求**：网络访问用于用户提供的订阅下载，以及播放列表、媒体分片和相关播放资源；不做遥测、不做播放行为上报、不采集设备标识。第三方服务仍会收到 IP、请求地址等网络信息，其日志政策不由应用控制。
- Manifest 仅申请 `INTERNET` 权限；`usesCleartextTraffic=true` 用于兼容内置与导入的 HTTP 地址。导入源不可枚举，当前未按域设置白名单，也未剥离用户输入 URL 中的令牌等参数，不能保证请求不含凭据。若后续加入登录 / 账号能力，必须改为 `networkSecurityConfig` 按域收紧，加入敏感配置前需收紧备份边界。
- 不实现应用内 WebView；如需打开外部网页，交给系统浏览器并另议交互。

## 验证

默认不运行 Gradle 构建、测试、Lint、安装或模拟器验证，编译门禁由工作流统一执行；用户明确要求验证时，按改动范围选择下列命令：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
git diff --check
```

- 获授权执行时 Lint 要求 0 errors；文档编辑可直接执行 `git diff --check` 与本地链接检查，不因此启动构建或设备任务。
- UI 改动的待验收范围至少考虑浅深色、大字体、RTL、窄宽屏、手势 / 三键导航、IME、进程重建恢复、前后台切换与播放中断恢复；按实际影响选取，不把清单写成已经通过。
- 静态阅读、构建、测试、Lint 和设备实测分别报告；历史通过结果不覆盖当前差异，未运行的检查如实标注「未运行」。
- 直播源可用性随时间变化，任何「源可用」的结论必须来自当次实测，不以代码存在信号源为依据。

## 文档与历史维护

- 文档是功能的一部分：功能、行为或约束变化时同步更新受影响的 README（中英）与本目录专题，保持「当前行为」与「历次修订」各自清楚，不为每次小改动新建重复专题。
- Markdown 使用 UTF-8、有语义的标题和正常换行；段落 / 列表 / 表格之间保留空行，代码用标注语言的代码块。仓库内引用用相对路径，避免绝对本机路径。
- 当前文档体系：根目录 `AGENTS.md`（规范）、`README.md` / `README.zh-CN.md`（入口）、`project-docs/`（工程专题）不随 APK 打包；`docs/legal/`（用户协议 / 隐私政策）与 `docs/version-history/`（用户更新日志）作为 assets 随包提供。后续新增专题先登记进[工程索引](project-docs/README.md)。
- 三份用户文档以 `#` 一级标题 + 概述 + `##` 章节 / 版本为结构，不在围栏代码块中放二级标题；`###` 等子标题交 Markwon 渲染。文档路由仅接受 `LocalDocument` 枚举；法律互链使用已登记文件名，外部 HTTP(S) 网页经确认交系统浏览器，不引入 WebView。版本日志按“开发中”与已知基础能力分开，不虚构发布日期。
- 内置频道目录、播放源出处与免责说明属于用户可见信息，更新源时同步 [README.zh-CN.md](README.zh-CN.md) 的对应章节。
- 来源记录需区分列表整理项目、实际流服务与节目权利方；公开可访问、来源署名或学习用途不代替内容授权。新增或替换地址需记录频道 id、线路标签、上游文件与版本／获取日期及改写说明；无法核对的出处明确标为待补，不把整个内置目录归给单一项目。
- 开发记录需说明触发原因、最终行为与实际检查范围；没有实测依据时不宣称可用性、性能或兼容性结论。
