# 工程文档索引

[English README](../README.md)　|　[中文 README](../README.zh-CN.md)

更新日期：2026-11-09。

本目录服务于研发、排查和代码审查，不会随 APK 打包。先确认自己要查的是“当前行为”还是“历史原因”，再选择入口：

- 当前行为以[技术设计](TECHNICAL_DESIGN.md)为准；
- 地址来源、内容抽样和许可边界以[内置频道与来源记录](CHANNEL_SOURCES.md)为准；
- 日期、触发原因和检查范围以[工程变更记录](IMPLEMENTATION_HISTORY.md)为准；
- 长期约束以根目录 [AGENTS.md](../AGENTS.md) 为准。

## 按问题查找

| 你要解决的问题 | 推荐入口 |
| --- | --- |
| 了解项目功能、构建方式和用户边界 | [中文 README](../README.zh-CN.md) 或 [English README](../README.md) |
| 理解架构、包结构、Room 和数据流 | [技术设计：架构与数据模型](TECHNICAL_DESIGN.md#2-架构与职责) |
| 排查主页状态、搜索、收藏或恢复 | [技术设计：主页数据流](TECHNICAL_DESIGN.md#5-主页数据流) |
| 排查 m3u 解析、下载、合并或去重 | [技术设计：m3u 解析与导入](TECHNICAL_DESIGN.md#6-m3u-解析与导入) |
| 排查播放、换源、清晰度或画中画 | [技术设计：播放器与信号源轮换](TECHNICAL_DESIGN.md#7-播放器与信号源轮换) |
| 排查同页选台、IME、焦点或窗口变化 | [技术设计：同页选台](TECHNICAL_DESIGN.md#8-同页选台) |
| 了解离线文档的读取、章节定位和动画 | [技术设计：关于页与离线文档](TECHNICAL_DESIGN.md#9-关于页与离线文档) |
| 核对频道数量、上游快照或删除地址 | [内置频道与来源记录](CHANNEL_SOURCES.md) |
| 查看某次修正为什么发生、检查了什么 | [工程变更记录](IMPLEMENTATION_HISTORY.md) |
| 查看未实现、未验证或未来计划 | [技术设计：网络、隐私与已知限制](TECHNICAL_DESIGN.md#11-网络隐私与已知限制) |
| 修改用户可见的更新说明、协议或隐私正文 | [更新日志](../docs/version-history/CHANGELOG.md)、[用户协议](../docs/legal/USER_AGREEMENT.md)、[隐私政策](../docs/legal/PRIVACY_POLICY.md) |
| 查看应用图标设计稿 | [tv-workshop-logo.svg](design/tv-workshop-logo.svg) |

## 文档分层

| 位置 | 面向读者 | 内容 | 是否打包进 APK |
| --- | --- | --- | --- |
| `README.md`、`README.zh-CN.md` | 使用者、贡献者 | 功能、构建、来源摘要、隐私和文档入口 | 否 |
| `AGENTS.md` | 开发者和审查者 | 长期架构、代码、资源、播放器、导入和验证约定 | 否 |
| `project-docs/TECHNICAL_DESIGN.md` | 开发者 | 当前实现和排查所需的技术细节 | 否 |
| `project-docs/CHANNEL_SOURCES.md` | 维护者、来源核对者 | 内置目录、上游快照、内容抽样、许可和反馈 | 否 |
| `project-docs/IMPLEMENTATION_HISTORY.md` | 开发者、审查者 | 按日期记录触发原因、改动和检查边界 | 否 |
| `docs/version-history/CHANGELOG.md` | 应用使用者 | 关于页展示的用户更新说明 | 是，assets |
| `docs/legal/USER_AGREEMENT.md` | 应用使用者 | 使用规则、内容权利和责任边界 | 是，assets |
| `docs/legal/PRIVACY_POLICY.md` | 应用使用者 | 数据、网络请求、备份和清理方式 | 是，assets |

## 维护规则

1. 修改功能行为时，先更新技术设计，再按影响范围同步 README、用户协议、隐私政策或更新日志。
2. 新增或替换内置地址时，必须在[来源记录](CHANNEL_SOURCES.md)写明频道 id、线路标签、上游文件、版本或获取日期及改写原因。
3. 历史记录只写当次触发原因、最终行为和实际检查范围；没有构建或设备证据时，不写成“已验证兼容”或“性能提升”。
4. 随包文档保持 `#` 一级标题、概述和 `##` 章节结构；章节正文中可以使用 `###`，围栏代码块内不要放二级标题。
5. 文档使用 UTF-8，仓库查阅用相对链接。随包文档只保留登记法律文件的本地互链，未打包资料使用 HTTP(S) 项目链接。拆分或改名后更新本索引并检查目标与锚点。
6. `docs/` 是应用内文档的唯一维护源；不要在 README 或工程专题中复制一份会随功能变化的完整正文。

## 当前验证口径

文档编辑可直接执行 `git diff --check` 和本地链接检查。构建、测试、Lint、安装、模拟器和真机任务默认不运行，只有明确要求时才执行，并在文档或回复中区分静态检查与运行时验证。直播源随时间变化，来源文本命中也不能当作可播放或内容授权证明。
