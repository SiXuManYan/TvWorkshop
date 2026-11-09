package com.sugar.tv.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.sugar.tv.BuildConfig
import java.io.IOException

/**
 * 调试包的本机播放诊断，区分缓冲等待、加载失败与解码掉帧，不驱动重试或换源。
 *
 * 仅写 Logcat，不落盘、不上报；不输出频道名、地址、请求头、异常消息或堆栈，避免泄露订阅令牌。
 * Analytics 回调与页面装载均在播放器的主线程执行，不另外轮询或采集设备信息。
 */
internal class PlayerDiagnostics : AnalyticsListener {

    /** 当前装载的媒体代次；新请求到来后忽略旧时间线的诊断事件。 */
    private var activeRequestId = -1L

    /** 仅记录线路位置，不记录可识别节目或订阅内容的数据。 */
    private var sourcePosition = ""

    /** 当前一次缓冲的开始时间，使用事件的单调时钟计算等待时长。 */
    private var bufferingStartedAtMs: Long? = null

    /** 区分首次接入与已就绪后的再次缓冲，不把换台启动当作播放中卡顿。 */
    private var hasReachedReady = false

    /** 最近的播放器带宽估计，仅作为缓冲和错误事件的辅助指标。 */
    private var estimatedBitrate = -1L

    /** 新请求重置统计；后续相同下标的新代次表示原线路重接。 */
    fun onSourceRequested(playRequestId: Long, sourceIndex: Int, sourceCount: Int) {
        if (!BuildConfig.DEBUG) return
        activeRequestId = playRequestId
        sourcePosition = "${sourceIndex + 1}/$sourceCount"
        bufferingStartedAtMs = null
        hasReachedReady = false
        estimatedBitrate = -1L
        Log.d(TAG, "request=$activeRequestId source=$sourcePosition event=source_request")
    }

    /** 记录缓冲开始和就绪时间，不把 BUFFERING 当作致命失败。 */
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (!isCurrentEvent(eventTime)) return
        when (state) {
            Player.STATE_BUFFERING -> {
                bufferingStartedAtMs = eventTime.realtimeMs
                Log.d(
                    TAG,
                    "${context(eventTime)} event=buffering phase=${if (hasReachedReady) "rebuffer" else "startup"}",
                )
            }
            Player.STATE_READY -> {
                val waitedMs = bufferingStartedAtMs?.let { eventTime.realtimeMs - it } ?: 0L
                bufferingStartedAtMs = null
                hasReachedReady = true
                Log.d(TAG, "${context(eventTime)} event=ready waitMs=$waitedMs")
            }
        }
    }

    /** 加载错误可能由 Media3 自行重试恢复，这里只记录状态码与异常类别。 */
    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        if (!isCurrentEvent(eventTime) || wasCanceled) return
        Log.w(
            TAG,
            "${context(eventTime)} event=load_error dataType=${mediaLoadData.dataType} " +
                "loadMs=${loadEventInfo.loadDurationMs} bytes=${loadEventInfo.bytesLoaded} ${failureDetails(error)}",
        )
    }

    /** 致命错误保留 Media3 错误码，以区别网络、直播窗口、解析与解码失败。 */
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        if (!isCurrentEvent(eventTime)) return
        val waitedMs = bufferingStartedAtMs?.let { eventTime.realtimeMs - it } ?: 0L
        Log.w(
            TAG,
            "${context(eventTime)} event=player_error code=${error.errorCodeName} " +
                "waitMs=$waitedMs ${failureDetails(error)}",
        )
    }

    /** 只保留最新估计值，不在每次分片下载后重复输出日志。 */
    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        if (isCurrentEvent(eventTime)) estimatedBitrate = bitrateEstimate
    }

    /** 记录实际解码的视频格式，供码率、带宽和设备掉帧对照。 */
    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        if (!isCurrentEvent(eventTime)) return
        Log.d(
            TAG,
            "${context(eventTime)} event=video_format width=${format.width} height=${format.height} " +
                "bitrate=${format.bitrate} mime=${format.sampleMimeType}",
        )
    }

    /** 缓冲充足时仍持续掉帧，需进一步检查编码、设备解码与渲染负载。 */
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        if (!isCurrentEvent(eventTime)) return
        Log.w(TAG, "${context(eventTime)} event=dropped_frames count=$droppedFrames elapsedMs=$elapsedMs")
    }

    /** 音频欠载与网络缓冲分别记录，避免将所有声音中断都归为源失效。 */
    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        if (!isCurrentEvent(eventTime)) return
        Log.w(TAG, "${context(eventTime)} event=audio_underrun elapsedSinceFeedMs=$elapsedSinceLastFeedMs")
    }

    /** 关闭正式包诊断，并通过事件自己的时间线拒绝旧媒体迟到事件。 */
    private fun isCurrentEvent(eventTime: AnalyticsListener.EventTime): Boolean =
        BuildConfig.DEBUG && eventTime.playRequestId() == activeRequestId

    /** 所有日志共用有限的技术上下文；带宽估计不等于源实际可持续下载速度。 */
    private fun context(eventTime: AnalyticsListener.EventTime): String =
        "request=$activeRequestId source=$sourcePosition bufferedMs=${eventTime.totalBufferedDurationMs} " +
            "bandwidthBps=$estimatedBitrate"

    /** 只读取异常类型与数字状态码，禁止异常文本中夹带 URL、Cookie 或令牌。 */
    private fun failureDetails(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(6).toList()
        val classes = causes.joinToString(">") { it.javaClass.simpleName }
        val httpStatus = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        return "causes=$classes httpStatus=${httpStatus ?: -1}"
    }

    private companion object {
        /** Android Studio Logcat 可用此标签筛出本页诊断。 */
        const val TAG = "TvPlayback"
    }
}

/** 从事件所属时间线读取媒体代次，不能用播放器当前媒体给旧回调重新贴标签。 */
internal fun AnalyticsListener.EventTime.playRequestId(): Long? {
    if (windowIndex !in 0 until timeline.windowCount) return null
    return timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId.toLongOrNull()
}
