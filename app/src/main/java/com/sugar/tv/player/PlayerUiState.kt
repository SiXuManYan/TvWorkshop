package com.sugar.tv.player

import com.sugar.tv.data.model.Channel
import com.sugar.tv.data.model.ChannelSource

/**
 * 选台面板的可恢复状态；只保存小型数据，不持有列表或视图。
 *
 * @property isOpen 是否正在浏览频道列表
 * @property query 原始查询文字，保留输入光标附近的空格，筛选时另行 trim
 */
data class ChannelPanelUiState(
    val isOpen: Boolean = false,
    val query: String = "",
)

/**
 * 视频清晰度档位：来自播放器 `Tracks` 回调中同一频道的多个视频格式。
 *
 * @property height 视频高度（像素），如 1080；展示标签由 UI 侧格式化
 * @property bitrate 该高度下的最高码率（bps），同高度多格式时取最大；未知为 -1
 */
data class QualityOption(
    val height: Int,
    val bitrate: Int,
)

/**
 * 清晰度选择模式。
 *
 * [AUTO] 交由 ExoPlayer 按带宽自适应；[HIGHEST] 强制锁最高档（不随带宽降档）；
 * [PINNED] 限制视频高度上限，仍可在上限内自适应；新选项无该高度时自动回落。
 */
enum class QualityMode {
    AUTO,
    HIGHEST,
    PINNED,
}

/**
 * 播放页不可变 UI 状态，由 [PlayerViewModel] 以 StateFlow 只读暴露。
 *
 * @property channel 当前频道；`null` 表示尚未加载完成或频道不存在（配合 [channelMissing] 区分）
 * @property sources 当前频道的信号源列表
 * @property currentSourceIndex 当前选中（正在 / 待播放）的信号源下标
 * @property isBuffering 是否处于缓冲中，驱动中央加载指示
 * @property allSourcesFailed 当前已在末路且播放失败；前面的线路可能未尝试，随后有限重试当前源
 * @property channelMissing 频道不存在（如进程恢复后频道已被删除）：显示错误浮层
 * @property isFavorite 当前频道是否已收藏
 * @property playRequestId 播放请求序号：换频道、换源、重试时递增；UI 侧据此装载 / 重装播放源
 *   （重试当前源时下标不变、仅序号递增，因此必须以序号而非下标判断是否重新装载）
 * @property qualityOptions 当前信号源可用的清晰度档位（按高度降序）；空列表表示未知或无视频，
 *   单档信息仍保留用于验证高度偏好，少于两档时 UI 隐藏清晰度入口
 * @property qualityMode 当前清晰度模式（自动 / 锁最高 / 锁定指定高度）
 * @property pinnedVideoHeight [QualityMode.PINNED] 模式下锁定的视频高度
 * @property qualityRequestId 清晰度请求序号：用户切换档位或自动回落时递增，UI 侧据此把
 *   `TrackSelectionParameters` 应用到播放器
 */
data class PlayerUiState(
    val channel: Channel? = null,
    val sources: List<ChannelSource> = emptyList(),
    val currentSourceIndex: Int = 0,
    val isBuffering: Boolean = false,
    val allSourcesFailed: Boolean = false,
    val channelMissing: Boolean = false,
    val isFavorite: Boolean = false,
    val playRequestId: Long = 0L,
    val qualityOptions: List<QualityOption> = emptyList(),
    val qualityMode: QualityMode = QualityMode.AUTO,
    val pinnedVideoHeight: Int = 0,
    val qualityRequestId: Long = 0L,
)
