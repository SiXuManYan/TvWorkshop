# 电视工坊

[![English](https://img.shields.io/badge/Language-English-blue?style=for-the-badge)](README.md)

一款 Android 直播电视播放器：在本机浏览、搜索并播放公开直播频道，支持收藏、最近观看、m3u / m3u8 订阅导入与信号源自动轮换。

当前源码版本：`1.0`。要求 Android 10（API 29）及以上。基于 Kotlin、Android View 体系 / XML、ViewBinding 与 MVVM 构建。

## 功能

| 功能 | 说明 |
|---|---|
| 频道分类浏览 | 最近观看 / 央视（含 CGTN）/ 卫视 / 外国频道 / 自定义五类，横向 Chip 单选切换 |
| 内置频道目录 | 随应用内置 66 个频道（央视 24、卫视 32、外国 10），多信号源按「主源 / 备用」组织 |
| 收藏 | 列表条目星标与播放页收藏按钮，写入本地 Room，并发切换不丢更新 |
| 最近观看 | 播放即记录，按最近时间倒序展示；清空自定义频道不影响收藏与观看记录 |
| 搜索 | 主页工具栏展开式搜索，按频道名包含匹配，输入 250ms 去抖 |
| 导入 m3u / m3u8 订阅 | 支持订阅地址（自动下载）或直接粘贴订阅正文；同名频道合并信号源，重复导入幂等；下载解析流式衔接，大列表不占双倍内存 |
| 信号源自动轮换 | 播放失败自动切下一路信号源，全部失败后停在错误浮层并做有限自动重试（最多 2 次，手动操作即取消），支持重试与手动换源 |
| 清晰度选择 | 多码率源（HLS master playlist）提供「自动 / 锁最高 / 固定档位」切换，固定单码率源自动隐藏入口；硬件解码优先，失败回退软解 |
| 关于与说明 | 主页菜单「关于」向用户说明频道来源、版权归属、源失效兜底与隐私边界（与上架说明同口径） |
| 横屏播放页 | 横屏全屏播放器：顶栏（返回 / 频道名 / 收藏）、底栏（上 / 下频道 / 频道列表 / 换源 / 画中画）、双击左右半区或横向轻扫换台＋换台 OSD、5 秒无操作自动隐藏、频道抽屉带名称过滤、直播起播缓冲调优（起播约 0.8 秒出画）；不后台播放 |

## 内置频道与直播源说明

内置频道与播放地址固化自公开网络上的第三方 m3u 订阅列表（采集于 2026-11-08，出处见[技术设计](project-docs/TECHNICAL_DESIGN.md)）。这些直播源由第三方公益维护：

- **可用性不保证**：直播源随时间失效或变化，播放失败时应用会自动尝试下一路信号源，但无法保证任何频道始终可看。
- **版权归各权利方**：应用本身不分发任何音视频内容，仅播放公开流地址；内容版权归属于相应的广播组织与权利方。
- **仅供学习研究**：本项目用于技术学习与研究，请勿用于商业用途；如权利方对某一流地址有异议，请自行删除对应的内置频道条目。

也可以通过「导入频道源」加入自己的订阅，替代内置目录的不足。

## 隐私

- 收藏、观看记录与导入频道全部只存本机 Room 数据库，本机处理，**不上传任何数据**。
- 应用无账号体系、无统计 / 崩溃上报 SDK；Manifest 仅申请 `INTERNET` 权限，网络访问只用于下载用户提供的订阅地址与播放公开直播流。

## 构建

用 Android Studio 打开仓库根目录，等待 Gradle 同步完成即可运行；或命令行执行：

```bash
./gradlew :app:assembleDebug
```

工具链：AGP 9.3.3（内置 Kotlin，无需 kotlin-android 插件）、Gradle Wrapper 9.5.0、KSP 2.3.11；compileSdk / targetSdk 37，minSdk 29。无需额外签名配置即可构建 Debug 包。

## 技术栈

- **UI**：Android View 体系 + XML + ViewBinding + Material 3（DayNight 浅深色），单 `:app` 模块；不使用 Compose。
- **架构**：MVVM——Activity / Fragment 只做生命周期、Insets、导航与渲染；业务状态为不可变 UiState + StateFlow；数据访问收敛在 `ChannelRepository`。
- **数据**：Room 2.8.4（收藏 / 最近观看 / 导入频道三张表，KSP 编译器）。
- **播放**：Media3 / ExoPlayer 1.8.0（含 HLS 扩展），播放器实例归播放页生命周期所有。
- **异步**：kotlinx.coroutines（IO / Default 调度器分离，StateFlow + Channel）。

## 文档导航

| 问题 | 入口 |
|---|---|
| 架构、数据流、Room 表结构与 m3u 解析规则 | [技术设计](project-docs/TECHNICAL_DESIGN.md) |
| 开发与审查规范（MVVM 边界、i18n、播放器边界等长期规则） | [AGENTS.md](AGENTS.md) |
| 工程文档索引 | [project-docs/README.md](project-docs/README.md) |
| English entry | [README.md](README.md) |
