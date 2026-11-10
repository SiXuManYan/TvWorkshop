package com.sugar.tv.player

import androidx.annotation.StringRes
import com.sugar.tv.R

/**
 * 画面缩放模式；仅改变 View 的显示方式，不改变视频编码、线路或媒体请求。
 *
 * @property labelRes 本地化显示名称
 */
enum class VideoDisplayMode(@StringRes val labelRes: Int) {
    /** 保持原始宽高比，完整显示画面。 */
    FIT(R.string.video_display_fit),

    /** 保持比例铺满，裁掉超出窗口的部分。 */
    ZOOM(R.string.video_display_zoom),

    /** 拉伸到窗口边界，可能改变画面比例。 */
    FILL(R.string.video_display_fill),
}

/**
 * 与媒体请求分离的观看设置，计时显示不触发频道或轨道重新渲染。
 *
 * @property displayMode 当前播放会话的画面模式
 * @property sleepDeadlineElapsedMs 基于单调时钟的到期时间；0 表示未启用
 * @property sleepDurationMinutes 用户选择的时长，用于菜单标记
 * @property sleepTimerExpired 到期后保持为 true，重建或恢复前台不得重新播放
 */
data class PlayerViewingOptions(
    val displayMode: VideoDisplayMode = VideoDisplayMode.FIT,
    val sleepDeadlineElapsedMs: Long = 0L,
    val sleepDurationMinutes: Int = 0,
    val sleepTimerExpired: Boolean = false,
)

/** 睡眠定时的无 Android 依赖计算，页面调度和进程恢复使用同一套边界。 */
object SleepTimer {

    /** 固定时长限制避免失效恢复值变成无限计时。 */
    val durationsMinutes: List<Int> = listOf(15, 30, 60, 90, 120)

    /** 计时单位统一为毫秒，不使用本地化格式参与计算。 */
    const val MILLIS_PER_MINUTE = 60_000L

    /** 只在进程恢复时根据墙上时钟换算剩余时长；过期值返回 0。 */
    fun restoreRemainingMillis(deadlineEpochMs: Long, nowEpochMs: Long): Long =
        (deadlineEpochMs - nowEpochMs).coerceIn(0L, durationsMinutes.last() * MILLIS_PER_MINUTE)

    /** 显示向上取整的分钟数，到期边界返回 0，避免提前显示“0 分钟后停止”。 */
    fun remainingMinutes(deadlineElapsedMs: Long, nowElapsedMs: Long): Int {
        val remaining = (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        return ((remaining + MILLIS_PER_MINUTE - 1L) / MILLIS_PER_MINUTE).toInt()
    }
}
