package com.sugar.tv.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sugar.tv.R
import com.sugar.tv.base.BaseActivity
import com.sugar.tv.data.model.ChannelCategory
import com.sugar.tv.databinding.ActivityPlayerBinding
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 播放页：横屏全屏直播播放器。
 *
 * 职责仅限生命周期、窗口与 Insets、ViewBinding 渲染、播放区手势与导航；业务状态全部由
 * [PlayerViewModel] 持有。ExoPlayer 实例归本页面所有：onStart 创建或复用、onStop 释放
 * （画中画中仍属可见前台不释放，其余情形直播不后台播放），换源 / 换频道复用同一实例
 * `setMediaItem` 平滑过渡；旋转由 `configChanges` 接管，不重建、不重复初始化。
 *
 * 手势：单击确认切换浮层显隐；双击左 / 右半区与横向轻扫按方向换台（配合换台 OSD）。
 */
class PlayerActivity : BaseActivity() {

    private lateinit var binding: ActivityPlayerBinding

    /** 与频道抽屉共享的播放 ViewModel（Activity 作用域）。 */
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(this)[PlayerViewModel::class.java]
    }

    /** 当前播放器实例；onStart 创建 / 复用，onStop 与 onDestroy 释放后置空。 */
    private var player: ExoPlayer? = null

    /** 已装载到播放器的播放请求序号；[NO_PLAY_REQUEST] 表示尚未装载（含释放后重置）。 */
    private var appliedPlayRequestId: Long = NO_PLAY_REQUEST

    /** 覆盖层（顶栏 + 底栏）当前是否可见。 */
    private var controlsVisible = true

    /** 最近一次渲染时错误浮层是否可见，用于驱动操作区在出错期间保持显示。 */
    private var errorOverlayVisible = false

    /** 已应用到播放器的清晰度请求序号；[NO_PLAY_REQUEST] 表示尚未应用（含释放后重置）。 */
    private var appliedQualityRequestId: Long = NO_PLAY_REQUEST

    /** 5 秒无操作自动隐藏操作区。 */
    private val hideControlsRunnable = Runnable { setControlsVisible(visible = false) }

    /** 最近一次 OSD 展示的频道 id：同一频道重复渲染不重播 OSD。 */
    private var lastOsdChannelId: String? = null

    /** OSD 停留结束后淡出。 */
    private val osdHideRunnable = Runnable {
        binding.tvPlayerOsd.animate().alpha(0f).setDuration(OSD_FADE_DURATION_MS)
            .withEndAction { binding.tvPlayerOsd.isVisible = false }
            .start()
    }

    /** 播放区手势识别：单击确认切换浮层显隐、双击换台、横向轻扫换台；构造期 Context 未挂载，在 onCreate 创建。 */
    private lateinit var channelGestureDetector: GestureDetector

    /** 播放器事件监听：缓冲状态同步、视频档位上报与播放失败自动换源。 */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val requestId = currentMediaRequestId() ?: return
            viewModel.onBufferingChanged(
                playRequestId = requestId,
                isBuffering = playbackState == Player.STATE_BUFFERING,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            // setMediaItem 会先清空轨道；这不是新源的真实档位，不能据此丢弃用户的高度偏好。
            if (tracks.isEmpty) return
            val requestId = currentMediaRequestId() ?: return
            viewModel.onVideoQualityOptionsChanged(
                playRequestId = requestId,
                options = extractQualityOptions(tracks),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            val requestId = currentMediaRequestId() ?: return
            // 当前信号源失败：交由 ViewModel 自动切下一路，全部失败则停在错误态
            viewModel.onPlaybackError(playRequestId = requestId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放页背景为纯黑：系统栏图标固定为亮色，不随系统深浅色翻转
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // GestureDetector 构造会读取 Resources：Activity 构造期 baseContext 尚未挂载，
        // 必须在 attach 之后的 onCreate 里创建，写成字段初始化器会导致启动即崩溃
        channelGestureDetector = GestureDetector(this, ChannelGestureListener())
        binding.root.keepScreenOn = true
        setupInsets()
        setupControls()
        scheduleControlsHide()
        observeViewModel()
    }

    /** 前台就绪后创建播放器、重放请求并恢复可操作的控件。 */
    override fun onStart() {
        super.onStart()
        ensurePlayer()
        // 不依赖生命周期收集器与播放器创建的先后顺序，前台恢复时显式重放当前请求。
        render(viewModel.uiState.value)
        if (!isInPictureInPictureMode) {
            setControlsVisible(visible = true)
            scheduleControlsHide()
        }
    }

    /** 非画中画退后台时撤销延迟 UI 任务并释放播放器。 */
    override fun onStop() {
        // 直播不后台播放：离开前台即释放播放器（画中画中仍处于可见前台，继续播放），
        // 回到前台重建实例并重新缓冲当前源
        if (!isInPictureInPictureMode) {
            binding.root.removeCallbacks(hideControlsRunnable)
            binding.root.removeCallbacks(osdHideRunnable)
            binding.tvPlayerOsd.animate().cancel()
            binding.tvPlayerOsd.isVisible = false
            releasePlayer()
        }
        super.onStop()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(hideControlsRunnable)
        binding.root.removeCallbacks(osdHideRunnable)
        releasePlayer()
        super.onDestroy()
    }

    /** 画中画隐藏覆盖层，退出时恢复操作区及其自动隐藏节奏。 */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 画中画只保留视频画面：覆盖层整体隐藏，退出后恢复操作区并重新计时自动隐藏
        binding.playerOverlay.isVisible = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.root.removeCallbacks(hideControlsRunnable)
        } else {
            setControlsVisible(visible = true)
            scheduleControlsHide()
        }
    }

    /** Edge-to-edge：顶栏避让状态栏与挖孔、底栏避让手势条；以 XML 初始 padding 为基准，重复分发不累加。 */
    private fun setupInsets() {
        val topBarStart = binding.playerTopBar.paddingStart
        val topBarTop = binding.playerTopBar.paddingTop
        val topBarEnd = binding.playerTopBar.paddingEnd
        val bottomBarStart = binding.playerBottomBar.paddingStart
        val bottomBarBottom = binding.playerBottomBar.paddingBottom
        val bottomBarEnd = binding.playerBottomBar.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.playerOverlay) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            binding.playerTopBar.updatePadding(
                left = topBarStart + safe.left,
                top = topBarTop + safe.top,
                right = topBarEnd + safe.right,
            )
            binding.playerBottomBar.updatePadding(
                left = bottomBarStart + safe.left,
                bottom = bottomBarBottom + safe.bottom,
                right = bottomBarEnd + safe.right,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /** 绑定控件与用户意图；播放区手势（单击 / 双击 / 横扫）与浮层空白点击均驱动显隐或换台。 */
    private fun setupControls() {
        binding.playerView.setOnClickListener { toggleControls() }
        // 触摸事件交给手势识别；单击确认后经 performClick 触发上面的点击监听，
        // 保证 TalkBack / 键盘激活与触摸行为一致
        binding.playerView.setOnTouchListener { _, event ->
            channelGestureDetector.onTouchEvent(event)
            true
        }
        binding.btnPlayerBack.setOnClickListener { finish() }
        binding.btnPlayerFavorite.setOnClickListener { viewModel.toggleFavorite() }
        binding.btnPlayerPrev.setOnClickListener {
            viewModel.prevChannel()
            scheduleControlsHide()
        }
        binding.btnPlayerNext.setOnClickListener {
            viewModel.nextChannel()
            scheduleControlsHide()
        }
        binding.btnPlayerSwitchSource.setOnClickListener {
            viewModel.nextSource()
            scheduleControlsHide()
        }
        binding.btnPlayerQuality.setOnClickListener { showQualityDialog() }
        binding.btnPlayerPip.setOnClickListener { enterPictureInPictureIfSupported() }
        binding.btnPlayerChannelList.setOnClickListener {
            showChannelSheet()
            scheduleControlsHide()
        }
        binding.btnPlayerRetry.setOnClickListener { viewModel.retry() }
        binding.btnPlayerErrorSwitchSource.setOnClickListener { viewModel.nextSource() }
    }

    /** 收集 ViewModel 状态与一次性事件；STARTED 起收集，回到 STARTED 时自动重放当前状态。 */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { render(it) }
                }
                launch {
                    viewModel.favoriteNotice.collect { noticeRes ->
                        Toast.makeText(this@PlayerActivity, noticeRes, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** 幂等渲染：由 UiState 驱动顶栏、操作条与浮层；播放 / 清晰度请求变化才推进对应标记。 */
    private fun render(state: PlayerUiState) {
        renderHeader(state)
        renderSourceControls(state)
        renderQualityControls(state)
        renderPlaybackOverlay(state)
        renderChannelOsd(state)
        if (state.playRequestId != appliedPlayRequestId && applyCurrentSource(state)) {
            appliedPlayRequestId = state.playRequestId
        }
        if (state.qualityRequestId != appliedQualityRequestId) {
            val target = player
            if (target != null) {
                applyQualityParameters(state, target)
                appliedQualityRequestId = state.qualityRequestId
            }
        }
    }

    /** 渲染顶栏：频道名、分类名与收藏按钮（图标、无障碍描述随收藏态切换）。 */
    private fun renderHeader(state: PlayerUiState) {
        val channel = state.channel
        binding.tvPlayerChannelName.text = when {
            channel != null -> channel.name
            state.channelMissing -> null
            else -> getString(R.string.loading_channels)
        }
        binding.tvPlayerCategoryName.text =
            channel?.let { getString(categoryLabelRes(it.category)) }
        binding.btnPlayerFavorite.setImageResource(
            if (state.isFavorite) {
                R.drawable.ic_player_favorite_filled
            } else {
                R.drawable.ic_player_favorite_outline
            },
        )
        binding.btnPlayerFavorite.contentDescription =
            getString(if (state.isFavorite) R.string.favorite_remove else R.string.favorite_add)
    }

    /** 渲染换源文案与可用性：底栏与错误浮层共用「信号源 i/n」占位文案。 */
    private fun renderSourceControls(state: PlayerUiState) {
        val sourceText = getString(
            R.string.switch_source_format,
            state.currentSourceIndex + 1,
            state.sources.size,
        )
        binding.btnPlayerSwitchSource.text = sourceText
        binding.btnPlayerErrorSwitchSource.text = sourceText
        val canSwitchSource = state.sources.size >= 2
        binding.btnPlayerSwitchSource.isEnabled = canSwitchSource
        binding.btnPlayerErrorSwitchSource.isEnabled = canSwitchSource
    }

    /**
     * 渲染清晰度入口：仅信号源提供多档画质时显示，按钮文案随当前模式切换
     * （自动 / 最高 / 具体高度如 1080p）。
     */
    private fun renderQualityControls(state: PlayerUiState) {
        binding.btnPlayerQuality.isVisible = state.qualityOptions.size >= 2
        binding.btnPlayerQuality.text = when (state.qualityMode) {
            QualityMode.AUTO -> getString(R.string.quality_auto)
            QualityMode.HIGHEST -> getString(R.string.quality_highest)
            QualityMode.PINNED -> formatHeightLabel(state.pinnedVideoHeight)
        }
    }

    /**
     * 清晰度选择对话框：自动（带宽自适应）/ 最高 / 高度上限（上限内仍可自适应）。
     * 单档位信号源不提供入口（按钮隐藏），打开对话框前以当前状态兜底。
     */
    private fun showQualityDialog() {
        val state = viewModel.uiState.value
        if (state.qualityOptions.size < 2) {
            return
        }
        val labels = buildList {
            add(getString(R.string.quality_auto))
            add(getString(R.string.quality_highest))
            state.qualityOptions.forEach { option -> add(formatHeightLabel(option.height)) }
        }
        val checkedIndex = when (state.qualityMode) {
            QualityMode.AUTO -> 0
            QualityMode.HIGHEST -> 1
            QualityMode.PINNED ->
                2 + state.qualityOptions.indexOfFirst { it.height == state.pinnedVideoHeight }
                    .coerceAtLeast(0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
                when (which) {
                    0 -> viewModel.selectQuality(mode = QualityMode.AUTO, height = 0)
                    1 -> viewModel.selectQuality(mode = QualityMode.HIGHEST, height = 0)
                    else ->
                        viewModel.selectQuality(
                            mode = QualityMode.PINNED,
                            height = state.qualityOptions[which - 2].height,
                        )
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 把当前清晰度模式写成播放器 `TrackSelectionParameters`（自动 / 锁最高 / 锁高度）。 */
    private fun applyQualityParameters(state: PlayerUiState, target: ExoPlayer) {
        target.trackSelectionParameters = target.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(
                /* maxVideoWidth = */ Integer.MAX_VALUE,
                /* maxVideoHeight = */
                if (state.qualityMode == QualityMode.PINNED) state.pinnedVideoHeight else Integer.MAX_VALUE,
            )
            .setForceHighestSupportedBitrate(state.qualityMode == QualityMode.HIGHEST)
            .build()
    }

    /**
     * 从播放器 `Tracks` 提取可选清晰度档位：仅统计被支持的视频格式，按高度去重
     * （同高度取最高码率），高度降序；保留单档信息用于验证高度偏好，入口由 UI 按档数隐藏。
     */
    private fun extractQualityOptions(tracks: Tracks): List<QualityOption> {
        val options = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter { index -> group.isTrackSupported(index) }
                    .map { index -> group.getTrackFormat(index) }
            }
            .filter { it.height > 0 }
            .groupBy { it.height }
            .map { (height, formats) ->
                QualityOption(
                    height = height,
                    bitrate = formats.maxOf { format ->
                        if (format.bitrate == androidx.media3.common.Format.NO_VALUE) {
                            -1
                        } else {
                            format.bitrate
                        }
                    },
                )
            }
            .sortedByDescending { it.height }
        return options
    }

    /** 档位标签：如 1080p——纯技术标签不含自然语言词，用 Locale.US 固定数字格式。 */
    private fun formatHeightLabel(height: Int): String =
        String.format(Locale.US, "%dp", height)

    /** 渲染中央缓冲与错误浮层，并在错误态切换时接管操作区的显隐节奏。 */
    private fun renderPlaybackOverlay(state: PlayerUiState) {
        val errorVisible = state.allSourcesFailed || state.channelMissing ||
            (state.channel != null && state.sources.isEmpty())
        binding.playerErrorOverlay.isVisible = errorVisible
        binding.tvPlayerErrorMessage.text =
            getString(if (state.allSourcesFailed) R.string.play_error_all_sources else R.string.play_error)
        binding.playerBuffering.isVisible = state.isBuffering && !errorVisible
        if (errorVisible != errorOverlayVisible) {
            errorOverlayVisible = errorVisible
            if (errorVisible) {
                // 出错期间操作区保持可见，等待用户重试或换源
                binding.root.removeCallbacks(hideControlsRunnable)
                setControlsVisible(visible = true)
            } else {
                scheduleControlsHide()
            }
        }
    }

    /** 渲染换台 OSD：频道变化时中央显示频道名，停留 [OSD_HIDE_DELAY_MS] 后淡出。 */
    private fun renderChannelOsd(state: PlayerUiState) {
        val channel = state.channel ?: return
        if (channel.id == lastOsdChannelId) return
        lastOsdChannelId = channel.id
        binding.tvPlayerOsd.text = channel.name
        binding.tvPlayerOsd.animate().cancel()
        binding.tvPlayerOsd.isVisible = true
        binding.tvPlayerOsd.animate().alpha(1f).setDuration(OSD_FADE_DURATION_MS).start()
        binding.root.removeCallbacks(osdHideRunnable)
        binding.root.postDelayed(osdHideRunnable, OSD_HIDE_DELAY_MS)
    }

    /**
     * 装载当前请求的信号源：同一播放器实例 setMediaItem + prepare + play 平滑过渡。
     *
     * 无可播信号源时清空旧媒体，避免错误浮层下仍播放上一个频道。
     *
     * @return 是否已应用请求；播放器未就绪时返回 `false`，等待前台恢复后重试
     */
    private fun applyCurrentSource(state: PlayerUiState): Boolean {
        val target = player ?: return false
        val url = state.sources.getOrNull(state.currentSourceIndex)?.url
        if (url == null) {
            target.clearMediaItems()
            return true
        }
        target.setMediaItem(buildMediaItem(url = url, playRequestId = state.playRequestId))
        target.prepare()
        target.play()
        return true
    }

    /**
     * 构造 MediaItem：对 m3u / m3u8 直播地址显式声明 HLS 类型，避免带查询串等场景下
     * 按扩展名推断失败；其余地址交由 ExoPlayer 按内容自动识别。
     */
    private fun buildMediaItem(url: String, playRequestId: Long): MediaItem {
        val lastSegment = Uri.parse(url).lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        val mimeType = if (lastSegment.endsWith(".m3u8") || lastSegment.endsWith(".m3u")) {
            MimeTypes.APPLICATION_M3U8
        } else {
            null
        }
        return MediaItem.Builder()
            .setMediaId(playRequestId.toString())
            .setUri(url)
            .setMimeType(mimeType)
            .build()
    }

    /** 从已装载媒体读取请求代次，用于拒绝新请求尚未装载时旧媒体发出的回调。 */
    private fun currentMediaRequestId(): Long? = player?.currentMediaItem?.mediaId?.toLongOrNull()

    /** 获取可用的播放器实例；不存在时按直播播放配置创建并绑定到播放视图（PlayerView）。 */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // 缩短起播 / 重缓冲阈值；这是媒体缓冲参数，实际出画时间还取决于网络和源，
        // min / max 缓冲维持本页配置，不据参数值承诺实测性能。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ MIN_BUFFER_MS,
                /* maxBufferMs = */ MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */ BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
        // 关闭扩展渲染器，初始化失败时尝试其他可用解码器；是否有软解取决于设备。
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
        val newPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        newPlayer.addListener(playerListener)
        player = newPlayer
        binding.playerView.player = newPlayer
        // 新实例为默认轨道参数：立即按当前清晰度模式恢复用户的档位偏好
        applyQualityParameters(viewModel.uiState.value, newPlayer)
        appliedQualityRequestId = viewModel.uiState.value.qualityRequestId
        return newPlayer
    }

    /** 释放播放器并重置装载标记；回到前台后由状态重放重新装载当前信号源。 */
    private fun releasePlayer() {
        val current = player ?: return
        current.removeListener(playerListener)
        // 先解除 Surface 关联，再释放实例，避免 PlayerView 在释放期间仍指向已失效播放器。
        binding.playerView.player = null
        player = null
        current.release()
        appliedPlayRequestId = NO_PLAY_REQUEST
        appliedQualityRequestId = NO_PLAY_REQUEST
    }

    /** 单击播放区：浮层显示时直接隐藏，隐藏时恢复并重新计时。 */
    private fun toggleControls() {
        if (controlsVisible) {
            setControlsVisible(visible = false)
        } else {
            setControlsVisible(visible = true)
            scheduleControlsHide()
        }
    }

    /** 顶栏与底栏整体淡入淡出；完全隐藏后退出 TalkBack 与键盘遍历，恢复时还原。 */
    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        for (bar in listOf(binding.playerTopBar, binding.playerBottomBar)) {
            bar.animate().cancel()
            if (visible) {
                bar.visibility = View.VISIBLE
                bar.animate().alpha(1f).setDuration(CONTROLS_FADE_DURATION_MS).start()
            } else {
                bar.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION_MS)
                    .withEndAction { if (!controlsVisible) bar.visibility = View.GONE }
                    .start()
            }
        }
    }

    /** 重新计时自动隐藏；错误浮层显示期间保持操作区常显。 */
    private fun scheduleControlsHide() {
        binding.root.removeCallbacks(hideControlsRunnable)
        if (!errorOverlayVisible) {
            binding.root.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
        }
    }

    /** 打开频道列表抽屉（与播放页共享同一 ViewModel）。 */
    private fun showChannelSheet() {
        if (supportFragmentManager.isStateSaved ||
            supportFragmentManager.findFragmentByTag(PlayerChannelsSheetFragment.TAG) != null
        ) return
        // 同步提交保证下一次连点能查到现有抽屉，避免叠加多个列表与观察任务。
        PlayerChannelsSheetFragment().showNow(supportFragmentManager, PlayerChannelsSheetFragment.TAG)
    }

    /**
     * 进入画中画：以 16:9 画幅继续播放当前频道；设备不支持或当前状态不允许时静默忽略，
     * 不打断前台播放。
     */
    private fun enterPictureInPictureIfSupported() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(PIP_ASPECT_RATIO)
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    /** 按点击 / 双击落点在屏幕左、右半区执行换台：左半区上一频道、右半区下一频道。 */
    private fun switchChannelByTapZone(x: Float) {
        if (x < binding.playerView.width / 2f) {
            viewModel.prevChannel()
        } else {
            viewModel.nextChannel()
        }
    }

    /**
     * 播放区手势监听：单击确认（约 300ms 去抖）切换浮层显隐；双击左右半区换台；
     * 横向轻扫（速度显著大于纵向）按方向换台，模拟遥控器 ch- / ch+。
     */
    private inner class ChannelGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(motionEvent: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {
            binding.playerView.performClick()
            return true
        }

        override fun onDoubleTap(motionEvent: MotionEvent): Boolean {
            switchChannelByTapZone(motionEvent.x)
            return true
        }

        override fun onFling(
            down: MotionEvent?,
            up: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val horizontal = abs(velocityX)
            if (down == null || horizontal < FLING_MIN_VELOCITY || horizontal < abs(velocityY) * FLING_RATIO) {
                return false
            }
            if (velocityX > 0) {
                viewModel.prevChannel() // 向右轻扫 → 上一频道
            } else {
                viewModel.nextChannel() // 向左轻扫 → 下一频道
            }
            return true
        }
    }

    /** 频道分类 → 顶栏分类文案资源。 */
    private fun categoryLabelRes(category: ChannelCategory): Int = when (category) {
        ChannelCategory.CCTV -> R.string.category_cctv
        ChannelCategory.SATELLITE -> R.string.category_satellite
        ChannelCategory.FOREIGN -> R.string.category_foreign
        ChannelCategory.CUSTOM -> R.string.category_custom
    }

    companion object {
        /** Intent / SavedStateHandle 通用键：频道 id。 */
        const val EXTRA_CHANNEL_ID = "channel_id"

        /** Intent / SavedStateHandle 通用键：起始信号源下标。 */
        const val EXTRA_SOURCE_INDEX = "source_index"

        /** 尚未装载任何播放请求的标记值。 */
        private const val NO_PLAY_REQUEST = -1L

        /** 操作区无操作自动隐藏延时。 */
        private const val CONTROLS_HIDE_DELAY_MS = 5_000L

        /** 操作区显隐淡入淡出时长。 */
        private const val CONTROLS_FADE_DURATION_MS = 200L

        /** 换台 OSD 停留时长。 */
        private const val OSD_HIDE_DELAY_MS = 2_000L

        /** OSD 淡入淡出时长。 */
        private const val OSD_FADE_DURATION_MS = 150L

        /** 直播起播（默认 15s/30s/2500ms/5000ms）：压缩起播与重缓冲首包等待。 */
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 30_000
        private const val BUFFER_FOR_PLAYBACK_MS = 800
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500

        /** 画中画画幅：直播画面普遍为 16:9。 */
        private val PIP_ASPECT_RATIO = Rational(16, 9)

        /** 横扫换台的最小横向速度（px/s），低于阈值视为无效手势。 */
        private const val FLING_MIN_VELOCITY = 2_000f

        /** 横扫换台要求横向速度至少为纵向速度的倍数，避免与纵向滑动误触。 */
        private const val FLING_RATIO = 2f

        /**
         * 构造播放页 Intent：频道列表、收藏、最近观看等入口跳转时使用。
         *
         * @param channelId 目标频道 id
         * @param sourceIndex 起始信号源下标，缺省从第一路开始
         */
        fun createIntent(context: Context, channelId: String, sourceIndex: Int = 0): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_SOURCE_INDEX, sourceIndex)
    }
}
