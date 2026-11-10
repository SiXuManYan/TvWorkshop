# 电视工坊：开发与审查约定

全局约定见 `~/.codex/AGENTS.md`。本文件集中维护本仓库的长期规则，不另建 `agent.md` 或其他大小写副本。

项目方向为 **Kotlin / XML / ViewBinding + MVVM**：保持 edge-to-edge 界面、直播播放与信号源自动轮换，尽量在本机处理数据，文档随功能维护。

## 1. 工作方式与阅读入口

- 用户在当前任务中的明确要求优先。常规实现选择自行完成，不反复询问是否继续。
- 修改前先读目标页面、对应数据层与相关文档，保留无关未提交修改。围绕问题完成最小连贯改动，不附带 UI 框架迁移、存储替换或产品扩展。
- 体验验收包含页面往返、前后台切换、进程重建和窗口变化，不能只考虑首次进入页面的静态效果。
- 当前规则以本文件为准，当前实现见技术设计。历史记录只适用于各自日期和修订，不自动成为待办或通过证据。

| 任务 | 阅读入口 |
| --- | --- |
| 了解功能、构建与使用边界 | [中文 README](README.zh-CN.md)、[English README](README.md) |
| 理解架构、数据流与播放器 | [技术设计](project-docs/TECHNICAL_DESIGN.md) |
| 核对内置地址、来源与许可 | [内置频道与来源记录](project-docs/CHANNEL_SOURCES.md) |
| 追溯调整原因与检查范围 | [工程变更记录](project-docs/IMPLEMENTATION_HISTORY.md) |
| 查找或维护文档 | [工程文档索引](project-docs/README.md) |

## 2. 技术栈与依赖边界

### 2.1 固定配置

| 项目 | 当前配置 |
| --- | --- |
| 模块 | 单 `:app`；`settings.gradle.kts` 仅 `include(":app")` |
| namespace / application id | `com.sugar.tv` |
| UI | Kotlin + Android View/XML + ViewBinding；**不使用 Compose，不引入 Compose 依赖** |
| AGP / Gradle Wrapper | 9.5.1 / 9.6.1；AGP 要求 Gradle ≥ 9.6.0、JDK ≥ 17 |
| Kotlin / KSP | AGP 内置 Kotlin，不添加 `kotlin-android`；KSP 2.3.13 仅用于 Room 编译器 |
| SDK / Java | minSdk 29，targetSdk / compileSdk 37，`compileOptions` Java 11 |
| Release | `optimization { enable = false }`，未开启 R8 |

关键依赖以 `gradle/libs.versions.toml` 为准：Media3 1.11.1（ExoPlayer、HLS、UI）、Room 2.8.5、Material 1.14.0、AppCompat 1.8.0、Lifecycle 2.11.0、Coroutines 1.11.0、Annotation 1.11.0、Core 1.19.1。升级默认采用稳定版，同步本文与技术设计，历史验证保留原日期和版本。

### 2.2 不扩展的边界

- XML 布局继续用 XML 修改，不迁移到 Compose。
- 本项目没有 `core_framework`、`module_image` 等库模块，不按其他项目模板引入。
- 页面用显式 `Intent` 导航，不为既有页面引入 Navigation 框架。
- 订阅下载只用 `HttpURLConnection`，信号源 JSON 只用 `org.json`；不引入 OkHttp、Retrofit、Gson、Coil、Glide 等第三方网络、图片或序列化库。
- 本地文档使用 Markwon core 4.6.2，与拼图工坊、RadioWorkshop 一致；仅渲染随包 Markdown，不启用网络图片插件。
- 改动混淆配置前，先确认模型的 `@Keep` 边界仍成立。

## 3. MVVM、状态与初始化

### 3.1 职责分工

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| Activity / Fragment、视图控制器 | 生命周期、窗口、Insets、导航、菜单、对话框、ViewBinding 渲染和播放器实例托管 | Room 查询、订阅下载、m3u 解析和业务状态机 |
| ViewModel | 不可变 UiState、用户意图、StateFlow、一次性事件、SavedStateHandle 恢复 | 持有 Activity、Fragment、View、Binding 或 Dialog |
| Repository | Room、订阅下载、解析调度、内置目录合并、收藏与观看记录；本地文档 IO 和后台排版 | 页面生命周期和 View 类型 |
| Adapter | ListAdapter / DiffUtil 渲染、一次性绑定监听、幂等回填 | 保存业务状态；点击应通过回调交给页面和 ViewModel |

页面包括 `MainActivity`、`PlayerActivity`、`ChannelManagerActivity`、`AboutActivity`、`LocalDocumentActivity`；选台几何布局由 `PlayerChannelsPanelController` 管理。ViewModel 包括 `BrowseViewModel`、`PlayerViewModel`、`ChannelManagerViewModel`、`LocalDocumentViewModel`，`AndroidViewModel` 只取 Application 级依赖。

### 3.2 状态与恢复

- 页面状态用不可变 `XxxUiState`，经只读 `StateFlow` 暴露；导入结果、收藏提示等一次性事件用 `Channel`。
- 页面统一在 `repeatOnLifecycle(STARTED)` 期间收集。
- `SavedStateHandle` 只保存频道 id、线路下标、枚举名、查询词等小型可序列化数据。Intent extras 与恢复键复用，如 `PlayerActivity.EXTRA_CHANNEL_ID`、`EXTRA_SOURCE_INDEX`。
- 状态读改写优先用 `MutableStateFlow.update`。
- 列表提交前比较 `currentList`，Chip 按布尔绝对值同步。异步写回必须核对请求代次，旧结果不能覆盖最新选择。
- 对话框和 Binding 由页面持有，在 `onDestroy` / `onDestroyView` 解除，不放进 ViewModel。

### 3.3 初始化顺序

Activity / Fragment 字段初始化器只做无上下文赋值。构造期 `baseContext` 尚未挂载，不能调用 `getResources`、`getString`，也不能构造内部会读取 Context / Resources 的对象（如 `GestureDetector`）。此类成员用 `lateinit`，在 `onCreate` 或 `onViewCreated` 创建。

ViewModel 在 `init` 启动任务之前，先声明并初始化任务引用、请求代次和任务读取的状态字段，不依赖编译器省略默认值赋值的行为。

## 4. Kotlin 与源码注释

- 多参数构造和函数调用优先命名参数；语义清楚的简短调用不强行套用，也不强制用于 Java、Android 框架 API 或函数类型。
- 新增或修改的类、函数和成员变量补充简短中文 KDoc，说明用途、约束、归属或边界。源码自然语言注释默认中文，不机械翻译代码。
- 主构造参数在类 KDoc 中使用 `@param` / `@property`，不在参数上方单独放文档注释。
- 取消、恢复、Insets、播放器释放等非显然逻辑保留必要的行内注释。
- `CancellationException` 必须原样上抛，不能被通用 `catch (Exception)` 吞掉后误报成功或失败；参照现有 ViewModel 写法。

## 5. 尺寸、颜色与文案

### 5.1 尺寸资源

`values/dimens.xml` 提供脚本生成的 `dp_1`～`dp_500`、`sp_1`～`sp_500`，勿手工增删单项。语义别名 `browse_list_max_width` 默认为 500dp，在 `values-w600dp` 覆盖为 480dp。

XML 正整数尺寸一律引用 `@dimen/dp_N` 或 `@dimen/sp_N`，不直接写 `10dp`、`10sp`。例外是约束布局的 `0dp`、资源引用、`match_parent`、`wrap_content` 等系统约定值。Kotlin 取像素用 `getDimension` 或 `getDimensionPixelSize`。

### 5.2 主题与 drawable

嵌套 drawable（selector、ripple、layer-list 的 item 内）禁止 `?attr`，直接引用 `@color/tv_*`。这类属性曾在 BottomSheetDialog 的 LayoutInflater 包装路径下于 Android 10 真机崩溃，编译无法发现；根级 vector 的主题填色可以沿用 AppCompat 包装路径。

新增需要区分浅深色的颜色时，在 `values/colors.xml` 和 `values-night/colors.xml` 成对维护，drawable 不写死颜色值。

### 5.3 文案与 i18n

界面文字使用占位符字符串资源和位置参数，不将字符串模板直接传给 `setText`。例如 `source_count_format` 的 `%1$d 路信号`、`switch_source_format` 的 `信号源 %1$d/%2$d`。不使用 `@Suppress` 或 `tools:ignore` 压制 `SetTextI18n`。

- 通用 key 同步维护全部 11 个目录：`values`、`values-en/ar/de/es/fr/ja/ko/pt/zh-rCN/zh-rTW`；当前各有 84 个 key（含 `strings_features.xml` 中的 28 个），提交前比较 key 集合与占位符。
- **2026-11-09 的明确例外**：关于页、更新日志、用户协议、隐私政策仅提供简体中文。新增文案集中在 `values/strings_documents.xml` 并标记 `translatable=false`，正文在 `docs/`；不复制中文到其他目录假作翻译。
- 频道名称是数据，保持官方命名，不放入 strings.xml，不参与多语言。
- 纯数字和十六进制格式化注意 Locale。

## 6. Edge-to-edge 与操作区

`BaseActivity` 在 `super.onCreate` 前调用 `enableEdgeToEdge()`。系统栏透明，内容延伸到系统栏后方，操作区按 Insets 避让。以 XML 初始值为基准设置绝对 padding，不重复累加；主页按各区域继续分发，播放、关于和文档页处理后返回 `CONSUMED`。

### 6.1 主页

- 列表铺满窗口，`clipToPadding=false`；Toolbar + 分类 Chip 为 `colorSurface` 半透明浮层，背景 alpha 为 230/255，不降低控件 alpha。
- 根容器避让左右系统栏与挖孔；浮层顶部避让状态栏与挖孔。
- 列表顶部留白跟随浮层实测高度；加载与空态位于浮层下方且可滚动。
- 列表和状态区底部 padding 为 `max(导航栏, IME) + 16dp`。

### 6.2 播放页

- 纯黑背景和白色前景，系统栏沉浸隐藏，允许边缘滑动临时唤出；顶底栏使用黑色渐隐遮罩，不压暗中央画面。
- 全屏时，顶底栏、错误内容和换台提示分别避让忽略可见性的系统栏与挖孔，保持稳定安全区。
- 选台时，根布局统一避让系统栏、挖孔和 IME，子控件不重复处理。
- 底部固定上一频道、频道列表、下一频道和末端设置；图标触达区至少 48dp，受限文案可省略但保留完整读屏描述。
- 无源不显示计数，单路禁用选源；没有可用设置动作时禁用设置；错误区仅在至少两路时提供一步换源。
- 设置根菜单与线路／画质子菜单使用固定深色背景、浅色文字和明确的禁用色；主题须同时覆盖 Material 弹窗背景与文字，不能只套 AppCompat 深色文字主题。

操作区通常 3 秒无操作后以 200ms 淡出并轻移。错误态、设置打开、持续触摸、键盘／遥控器焦点或读屏触摸浏览期间不自动隐藏，窗口失焦暂停计时。隐藏后设为 `INVISIBLE`，保留布局尺寸并退出 TalkBack 遍历；方向键可以唤出，错误态不能手动隐藏。

### 6.3 关于与文档页

使用 DayNight 信息卡和阅读卡，内容最大宽度 500dp，章节导航固定在顶部。根容器避让系统栏、挖孔和 IME，列表与正文不重复避让。

频道管理页同样使用 DayNight 和 500dp 最大宽度。管理操作与空态作为列表头部滚动，避免短窗口下固定操作区挤掉内容；首次数据提交后放行位置恢复，合法空目录也要保留头部滚动位置。

## 7. 播放器、换源与选台

### 7.1 生命周期与装载

ExoPlayer 归 `PlayerActivity` 持有。`onStart` 创建或复用，`onStop` 和 `onDestroy` 释放；**画中画属于可见前台，不释放播放器**，判断用 `isInPictureInPictureMode`。普通退后台释放，回前台由状态重放装载当前线路；释放时将 `appliedPlayRequestId` 重置为 -1。

ViewModel 只决定应播放哪一路。`playRequestId` 递增驱动 Activity 用同一实例执行 `setMediaItem + prepare + play`，换频道、换源和重试不重建播放器。`.m3u` / `.m3u8` 路径显式设置 `MimeTypes.APPLICATION_M3U8`，包括带查询串的地址；其余交播放器识别。

Manifest 保持 `sensorLandscape`、`singleTop`、`exported=false` 和 `supportsPictureInPicture`；`configChanges` 包含 orientation、screenSize、screenLayout、smallestScreenSize、keyboardHidden，旋转不重复初始化。

### 7.2 播放器配置

- 音频使用 `USAGE_MEDIA`，启用 `handleAudioFocus` 和 `handleAudioBecomingNoisy`。
- `DefaultLoadControl` 起播 1500ms、重缓冲 3000ms，min 15s / max 30s；直播目标延迟可能进一步限制实际阈值，这些不是实际出画耗时或性能提升的证据。
- 扩展渲染器为 `EXTENSION_RENDERER_MODE_OFF`，启用 `setEnableDecoderFallback(true)`。备用解码器由设备决定，不保证软解或消除所有黑屏。
- `PlayerView` 使用 `use_controller=false` 和 `surface_type=surface_view`，操作区自绘；`keepScreenOn=true`。

### 7.3 换频道、轮换与重试

- 上下频道按全集顺序循环回绕。同频道重复加载不重新起播，新频道从第一路开始，起始下标经 `resolveStartIndex` 写回恢复句柄。
- 连续换台以上一次请求目标为基准，先取消旧加载，写回前核对最新请求；观看记录跟随核对后的结果落库。
- 媒体回调使用 `AnalyticsListener.EventTime` 所属时间线中 `MediaItem.mediaId` 的请求序号，不能用播放器当前媒体替旧事件贴标签；过期代次不得更新当前状态。
- 普通缓冲与可恢复的分片加载错误不触发换源。致命 `ERROR_CODE_BEHIND_LIVE_WINDOW` 先在原线路默认直播位置重接一次，同一恢复请求再次失败才按下标顺序切下一路；其他致命错误直接顺序切源。末路失败进入 `allSourcesFailed`，延迟 8 秒重试当前源，最多 2 次。
- 换频道、手动重试和换源都会取消待执行重试；换频道与手动重试归零计数，换源保留计数。`nextSource` 循环切换并可从错误态恢复。
- 线路菜单校验媒体请求代次与当前选项，重复选择正常线路不重装。换频道、自动换源、画质偏好或选项变化时关闭旧菜单；退后台和进入画中画同时关闭根菜单与独立子菜单。
- `TvPlayback` 诊断只在调试包写本机 Logcat，记录缓冲、错误码、HTTP 状态和掉帧等技术指标；不记录频道名、URL、令牌、异常消息或堆栈，不落盘、不上报，不驱动播放策略。

### 7.4 清晰度

`onTracksChanged` 按轨道支持状态过滤视频格式，按高度去重，同高度取最高码率。保留单档信息用于偏好校验，不足两种高度时隐藏入口，装载期临时空轨道不清除偏好。

模式为自动、最高（`setForceHighestSupportedBitrate`）和高度上限（`setMaxVideoSize`，仍在上限内自适应）。所选高度在真实新选项中不存在时回落自动；用户切换和自动回落均递增 `qualityRequestId`，新实例立即恢复当前偏好。

### 7.5 手势、提示与画中画

单击经约 300ms 确认后通过 `performClick` 切换操作区，保持触摸、TalkBack 与键盘一致。正常观看时，双击左／右半区和满足速度、横纵比条件的横扫用于换台。只有操作区隐藏时才在顶部起始边缘显示频道名，1.5 秒后淡出，不重复显示标题。

画中画以 16:9 画幅进入；设备不支持时隐藏入口，调用仍检查能力。进入后关闭面板、隐藏覆盖层，退出后恢复操作区并重启隐藏计时，不打断前台播放。

### 7.6 同页选台

- 视频与列表为兄弟节点，不使用覆盖画面的 BottomSheet 或抽屉；可用宽度至少 600dp 时列表占末端 280～320dp，窄窗口视频在上、列表在下。
- 选台期间隐藏顶底栏与 OSD。点选频道、关闭按钮、系统返回或点击预览均收起列表，恢复全屏；预览不执行双击或横扫换台。
- 查询和开关由 ViewModel / SavedStateHandle 保存。过滤在 Default 执行，只在面板可见且页面 STARTED 时收集。
- 主动重开清空查询并定位当前频道一次；重建保留查询和滚动位置。
- 不自动弹出键盘，按 IME 与实际窗口空间重排；进入画中画关闭面板并恢复全尺寸视频。

### 7.7 观看设置

- 画面模式使用完整显示、等比裁切和拉伸，仅设置 `PlayerView.resizeMode`，不重装媒体；偏好仅在当前播放页会话恢复。
- 睡眠定时按单调时钟核对到期，进程恢复用已保存的墙上时钟截止值换算，不重新计满原时长。画中画、前后台和进程恢复均不能绕过到期停止；起播前必须先检查。
- 到期取消待执行加载和重试，先释放播放器再退出；页面销毁移除计时回调。不创建后台播放服务、系统闹钟或设备关机功能。

## 8. 性能与协程

- 网络、Room、解析和文件访问放 IO；频道合并、状态合成与目录过滤放 Default。主线程只处理 View 和轻量渲染，不用 `runBlocking`。
- 主页搜索在写入侧 250ms 去抖，清空立即生效；状态流用 `WhileSubscribed(5000)`，短暂导航不停止上游，5 秒无订阅后停采。
- 目录 id 索引只在全集变化时重建。列表使用稳定频道 id、ListAdapter / DiffUtil，相等结果不重复提交，不做无谓重绘。
- 订阅下载逐行交给 `M3uParser.parse(BufferedReader)`。与内置目录重名比对预建“归一化名称 → 地址”索引，避免逐条扫描整个内置目录。
- 导入地址用集合保序去重；DAO `INSERT IGNORE` 原子跳过已有 id，按真实 rowid 统计，不预读全部旧频道正文。
- 收藏切换使用 `FavoriteChannelDao.toggle` 单条事务；主页按点击顺序写入，乐观覆盖同步取值并由相同代次释放，宽限 1 秒。播放页星标只认仓库流。

主页列表启用 `setHasFixedSize`（列表尺寸不依赖条目内容，不要求条目等高），离屏缓存 4 条。关闭变更动画，保留增删和移动动画；异步目录用 `PREVENT_WHEN_EMPTY`，首次保留位置，后续切分类才回顶。Snackbar 只保留最新一条，收藏点击带 `CONTEXT_CLICK` 触感，名称最多两行，摘要可换行且不挤占收藏触达区。

## 9. 持久化与模型兼容

网络、序列化、反射相关模型统一加 `androidx.annotation.Keep`：`Channel`、`ChannelSource`、`ChannelCategory`、`SourceCodec`、`M3uEntry` 和三张 Room 实体。`@Keep` 只保护结构，不增加网络能力，也不替代隐私披露。

信号源格式固定为包含 `label`、`url` 的 JSON 数组。字段或格式修改必须兼容旧数据，解码容错跳过坏元素。Room schema 当前为 version 1、`exportSchema=false`，改表必须评估迁移，不能静默丢数据。

## 10. m3u 导入

详细解析与保存流程见[技术设计](project-docs/TECHNICAL_DESIGN.md#6-m3u-解析与导入)，修改时保持以下规则：

### 10.1 输入与解析

- 主页“导入频道源”对话框接受粘贴：单行 HTTP(S) 地址下载，其他输入按正文解析，支持多行裸 URL，scheme 不区分大小写。频道管理页另提供系统文件选择器，按 UTF-8 流式解析 m3u / m3u8。
- 说明独立换行，标签保持简短，输入区显示 4～6 行并可滚动，不截断正文；短窗口下对话框内容也可滚动。
- 兼容 BOM、`\r\n`；读取 `tvg-id`、`tvg-name`、`group-title`；名称取首个未被引号包裹的逗号之后内容，连续多条 EXTINF 可以共用一个 URL。
- 地址须有合法主机和端口；连接、读取超时各 10 秒，非 2xx 抛错，字符集优先响应头，否则 UTF-8。

### 10.2 合并与保存

- 同一批次按“去空白、忽略大小写的名称”合并，线路按序去重。
- 与内置同名时剔除已固化地址；全部重复跳过，含新地址时保存为独立导入频道。
- id 为 `m3u:` 加 `SHA-256(名称 + "\n" + 首个新 URL)` 前 12 位。已有 id 直接跳过，不更新其他线路；名称写法或首个新 URL 变化可能新增记录。
- 只保存当次结果，不保存订阅 URL，不定时同步。
- 名称或分组含“央视／CCTV／CNTV／CGTN”归央视，含“卫视”归卫视，其他含 CJK 归自定义，其余归外国频道。
- “清空自定义频道”删除全部导入记录，不限分类，不删除收藏和观看记录；频道消失后列表自动跳过弱关联记录。
- 频道管理页的单个删除仅删除目标导入身份；导出使用全部导入频道的有序快照，不受搜索影响，不包含内置目录、收藏、观看记录或媒体。完整 URL 可能含令牌，导出前提示保管；系统文件授权按次使用，不保存 URI。

### 10.3 错误处理

地址非法或没有可播放频道抛 `IllegalArgumentException`，提示格式错误；下载失败抛 `IOException`，提示网络不可用；其余异常显示通用失败。取消异常原样上抛。

## 11. 网络与隐私

- 收藏、最近观看和导入频道保存于本机 Room；应用无账号、统计／崩溃 SDK 或开发者数据收集服务，不主动上报这些记录，不做播放遥测或设备标识采集。
- `allowBackup=true`，备份规则未排除 Room。数据可能进入系统云备份或设备迁移，不能承诺始终不离开设备。
- 网络用于用户订阅下载，以及播放列表、媒体分片和相关资源。第三方仍会收到 IP、请求地址等信息，其日志不由应用控制。
- 导出频道列表会将名称、分组和完整地址写入用户所选文档位置；清理应用记录不会删除外部文件，云文件提供器按自身政策传输和保存文件。
- Manifest 仅申请 `INTERNET`；`usesCleartextTraffic=true` 用于兼容 HTTP。导入源不可枚举，当前没有域白名单，也不剥离 URL 中的令牌等参数。
- 若增加登录／账号，必须改用 `networkSecurityConfig` 按域收紧；加入敏感配置前也需收紧备份边界。
- 不实现 WebView；外部网页交系统浏览器，新增相关交互需单独考虑。现有文档外链按确认流程处理。

## 12. 验证与报告

默认不运行 Gradle 构建、测试、Lint、安装或模拟器任务，编译门禁由工作流统一执行。用户明确要求时，按改动范围选择：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
git diff --check
```

- 获授权运行的 Lint 要求 0 errors；文档编辑可直接检查差异格式、本地链接与锚点，不因此启动构建或设备任务。
- UI 按实际影响覆盖浅深色、大字体、RTL、窄宽窗口、导航模式、IME、进程重建、前后台和播放中断恢复，不把待验收清单写成已通过。
- 静态阅读、构建、测试、Lint 和设备实测分开报告；历史通过不覆盖当前差异，未运行就明确写“未运行”。
- 直播源可用性随时间变化，只能引用当次实测，不能因源码存在 URL 就宣称可播放。

## 13. 文档、来源与历史维护

### 13.1 文档职责

- `README.md`、`README.zh-CN.md`：项目入口、功能、使用边界和构建方式。
- `AGENTS.md`：长期开发规则，只在根目录维护一份。
- `project-docs/TECHNICAL_DESIGN.md`：当前实现。
- `project-docs/CHANNEL_SOURCES.md`：内置目录、地址来源和内容抽样。
- `project-docs/IMPLEMENTATION_HISTORY.md`：按日期记录触发原因、改动与实际检查。
- `docs/legal/`、`docs/version-history/`：面向用户的正文，作为 assets 打包；其他文档不打包。

功能变化时同步相关文档，不为每次小改动新建重复专题。新增专题先登记[工程索引](project-docs/README.md)。使用 UTF-8、语义标题和正常段落，段落／列表／表格之间留空行，代码块标注语言，仓库查阅用相对链接。

### 13.2 随包文档

正文采用 `#` 一级标题、概述、`##` 章节／版本结构；三级以下标题交 Markwon 渲染，围栏代码块不放二级标题。日志区分开发中与已知基础能力，不虚构发布日期。

路由只接受 `LocalDocument` 枚举；法律互链用登记文件名，工程文档等未打包内容使用 HTTP(S) 项目链接，经确认后交浏览器打开，不引入 WebView。

章节折叠由 Holder 独立执行 240ms 高度、透明度和箭头联动。反向操作从当前视觉状态接续；首屏、恢复、离屏和系统关闭动画直接到目标状态。收起立即退出正文焦点与读屏遍历，结束后清除链接并恢复 `wrap_content`；章节定位等待布局稳定，回收和销毁取消旧回调。

### 13.3 来源与历史

更新内置目录、地址出处或免责边界时，同步双语 README 和来源记录。新增或替换地址记录频道 id、线路标签、上游文件、版本／获取日期及改写原因；无法核对的明确标为待补，不能把全部目录归给单一项目。

区分列表整理者、实际流服务与节目权利方。公开访问、署名和学习用途不替代授权。工程记录说明触发原因、最终行为和当次检查范围；没有实测依据时，不宣称可用性、性能或兼容性提升。
