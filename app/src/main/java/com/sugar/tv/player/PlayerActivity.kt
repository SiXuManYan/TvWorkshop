package com.sugar.tv.player

import android.animation.ValueAnimator
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
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
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
import com.sugar.tv.R
import com.sugar.tv.base.BaseActivity
import com.sugar.tv.data.model.ChannelCategory
import com.sugar.tv.databinding.ActivityPlayerBinding
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 播放页：横屏全屏直播播放器。
 *
 * 职责仅限生命周期、窗口与 Insets、ViewBinding 渲染、播放区手势与导航；业务状态全部由
 * [PlayerViewModel] 持有。ExoPlayer 实例归本页面所有：onStart 创建或复用、onStop 释放
 * （画中画中仍属可见前台不释放，其余情形直播不后台播放），换源 / 换频道复用同一实例
 * `setMediaItem` 平滑过渡；旋转由 `configChanges` 接管，不重建、不重复初始化。
 *
 * 常用换台入口位于底部；选台时视频与列表分别占位，选源、画质与画中画收进深色设置菜单。
 * 手势：单击确认切换浮层显隐；双击左 / 右半区与横向轻扫按方向换台（配合边缘换台提示）。
 */
class PlayerActivity : BaseActivity() {

    /** 页面级视图绑定，只在 Context 挂载后的 onCreate 创建。 */
    private lateinit var binding: ActivityPlayerBinding

    /** 播放与选台查询的业务状态，不由视图控制器持有。 */
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(this)[PlayerViewModel::class.java]
    }

    /** 当前播放器实例；onStart 创建 / 复用，onStop 与 onDestroy 释放后置空。 */
    private var player: ExoPlayer? = null

    /** 已装载到播放器的播放请求序号；[NO_PLAY_REQUEST] 表示尚未装载（含释放后重置）。 */
    private var appliedPlayRequestId: Long = NO_PLAY_REQUEST

    /** 覆盖层（顶栏 + 底栏）当前是否可见。 */
    private var controlsVisible = true

    /** 面板只管理视图和分区几何，播放器始终归 Activity 托管。 */
    private lateinit var channelsPanelController: PlayerChannelsPanelController

    /** 最近渲染的面板显隐，用于隔离视频手势、浮层和自动隐藏逻辑。 */
    private var channelPanelVisible = false

    /** 选台时系统返回先收起面板，再次返回才离开播放器。 */
    private val channelPanelBackCallback = object : OnBackPressedCallback(false) {
        /** 保留当前频道，只结束选台操作。 */
        override fun handleOnBackPressed() {
            viewModel.closeChannelPanel()
        }
    }

    /** 最近一次渲染时错误浮层是否可见，用于驱动操作区在出错期间保持显示。 */
    private var errorOverlayVisible = false

    /** 已应用到播放器的清晰度请求序号；[NO_PLAY_REQUEST] 表示尚未应用（含释放后重置）。 */
    private var appliedQualityRequestId: Long = NO_PLAY_REQUEST

    /** 页面可见期间才允许计时，避免窗口失焦或退后台后重新挂上隐藏任务。 */
    private var controlsStarted = false

    /** 触摸尚未结束时不隐藏操作区，避免长按或持续滚动中途失去按钮。 */
    private var controlsTouchInProgress = false

    /** 系统读屏状态在 onCreate 获取，不在 Activity 构造阶段访问 Context。 */
    private var accessibilityManager: AccessibilityManager? = null

    /** 页面持有的临时设置菜单，退后台或媒体选项变化时关闭。 */
    private var settingsPopup: PopupMenu? = null

    /** 菜单打开时的媒体快照，防止自动换源后继续操作旧线路或旧画质。 */
    private var settingsSnapshot: PlayerUiState? = null

    /** 执行时再次核对焦点与读屏状态，避免计时期间开始操作的用户失去控件。 */
    private val hideControlsRunnable = Runnable {
        if (canAutoHideControls()) setControlsVisible(visible = false)
    }

    /** 键盘 / 遥控器焦点离开操作条后重新计时，停留在按钮上时保持常显。 */
    private val controlsFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        scheduleControlsHide()
    }

    /** 读屏触摸浏览启用时显示操作区，关闭后按当前焦点恢复自动隐藏。 */
    private val touchExplorationListener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        if (enabled && controlsStarted && !isInPictureInPictureMode) {
            setControlsVisible(visible = true)
        }
        scheduleControlsHide()
    }

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

    /** 创建视图、手势与无障碍监听，所有 Context 相关对象均在挂载后初始化。 */
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
        accessibilityManager = getSystemService(AccessibilityManager::class.java)
        accessibilityManager?.addTouchExplorationStateChangeListener(touchExplorationListener)
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(controlsFocusListener)
        binding.root.keepScreenOn = true
        WindowCompat.getInsetsController(window, binding.root).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setupControls()
        setupChannelPanel(
            restoringOpenPanel = savedInstanceState != null && viewModel.channelPanelState.value.isOpen,
        )
        setupInsets()
        scheduleControlsHide()
        observeViewModel()
    }

    /** 前台就绪后创建播放器、重放请求并恢复可操作的控件。 */
    override fun onStart() {
        super.onStart()
        controlsStarted = true
        hideSystemBars()
        ensurePlayer()
        renderChannelPanel(viewModel.channelPanelState.value)
        // 不依赖生命周期收集器与播放器创建的先后顺序，前台恢复时显式重放当前请求。
        render(viewModel.uiState.value)
        if (!isInPictureInPictureMode) {
            setControlsVisible(visible = true)
            scheduleControlsHide()
        }
    }

    /** 非画中画退后台时撤销延迟 UI 任务并释放播放器。 */
    override fun onStop() {
        controlsStarted = false
        controlsTouchInProgress = false
        channelsPanelController.hideKeyboard()
        closePlaybackSettings()
        binding.root.removeCallbacks(hideControlsRunnable)
        binding.playerTopBar.animate().cancel()
        binding.playerBottomBar.animate().cancel()
        // 直播不后台播放：离开前台即释放播放器（画中画中仍处于可见前台，继续播放），
        // 回到前台重建实例并重新缓冲当前源
        if (!isInPictureInPictureMode) {
            binding.root.removeCallbacks(osdHideRunnable)
            binding.tvPlayerOsd.animate().cancel()
            binding.tvPlayerOsd.isVisible = false
            releasePlayer()
        }
        super.onStop()
    }

    /** 注销系统与视图监听，释放页面级任务和播放器引用。 */
    override fun onDestroy() {
        accessibilityManager?.removeTouchExplorationStateChangeListener(touchExplorationListener)
        binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(controlsFocusListener)
        binding.root.removeCallbacks(hideControlsRunnable)
        binding.root.removeCallbacks(osdHideRunnable)
        closePlaybackSettings()
        binding.tvPlayerOsd.animate().cancel()
        channelsPanelController.dispose()
        releasePlayer()
        super.onDestroy()
    }

    /** 所有触摸和按键操作都重置计时，包括收藏、重试和设置菜单。 */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::binding.isInitialized) scheduleControlsHide()
    }

    /** 连续触摸期间暂停计时，松手或取消后再开始完整的观看倒计时。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::binding.isInitialized && event.actionMasked == MotionEvent.ACTION_DOWN) {
            controlsTouchInProgress = true
            binding.root.removeCallbacks(hideControlsRunnable)
        }
        val handled = super.dispatchTouchEvent(event)
        if (::binding.isInitialized &&
            (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            controlsTouchInProgress = false
            scheduleControlsHide()
        }
        return handled
    }

    /** 对话框关闭后重新计时，窗口被遮挡期间不让操作区在背后消失。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::binding.isInitialized) {
            if (hasFocus && controlsStarted) hideSystemBars()
            scheduleControlsHide()
        }
    }

    /** 隐藏状态下方向键先唤出操作条，再交系统执行常规焦点移动。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::binding.isInitialized && event.action == KeyEvent.ACTION_DOWN &&
            !isInPictureInPictureMode && !channelPanelVisible && event.keyCode in CONTROL_NAVIGATION_KEYS
        ) {
            setControlsVisible(visible = true)
            scheduleControlsHide()
        }
        return super.dispatchKeyEvent(event)
    }

    /** 画中画隐藏覆盖层，退出时恢复操作区及其自动隐藏节奏。 */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 画中画只保留视频画面：覆盖层整体隐藏，退出后恢复操作区并重新计时自动隐藏
        binding.playerOverlay.isVisible = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // 画中画只给视频分配空间；选台面板不随小窗恢复，返回后可再次主动打开。
            viewModel.closeChannelPanel()
            renderChannelPanel(viewModel.channelPanelState.value)
            closePlaybackSettings()
            binding.root.removeCallbacks(hideControlsRunnable)
            hideChannelOsd()
        } else {
            hideSystemBars()
            setControlsVisible(visible = true)
            scheduleControlsHide()
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /** 播放期间隐藏系统栏，边缘滑动可临时唤出；控件与系统栏的显示互不绑定。 */
    private fun hideSystemBars() {
        if (!isInPictureInPictureMode) {
            WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 全屏由浮层避让；选台由根布局统一避让系统栏和 IME，防止两套 padding 叠加。 */
    private fun setupInsets() {
        val topBarStart = binding.playerTopBar.paddingStart
        val topBarTop = binding.playerTopBar.paddingTop
        val topBarEnd = binding.playerTopBar.paddingEnd
        val bottomBarStart = binding.playerBottomBar.paddingStart
        val bottomBarBottom = binding.playerBottomBar.paddingBottom
        val bottomBarEnd = binding.playerBottomBar.paddingEnd
        val errorMargin = resources.getDimensionPixelSize(R.dimen.dp_16)
        val osdTop = resources.getDimensionPixelSize(R.dimen.dp_12)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            // 忽略可见性可避免系统栏短暂滑出时按钮和换台提示上下跳动。
            val windowSafe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            channelsPanelController.applyInsets(
                safe = if (isInPictureInPictureMode) Insets.NONE else windowSafe,
                keyboardBottom = if (isInPictureInPictureMode) 0 else insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            val safe = if (channelPanelVisible || isInPictureInPictureMode) Insets.NONE else windowSafe
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
            // 错误卡片在操作条之间布局，水平方向同样避让横屏挖孔与侧边导航栏。
            binding.playerErrorOverlay.updateLayoutParams<ConstraintLayout.LayoutParams> {
                leftMargin = errorMargin + safe.left
                rightMargin = errorMargin + safe.right
                marginStart = leftMargin
                marginEnd = rightMargin
                if (binding.playerOverlay.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    marginStart = rightMargin
                    marginEnd = leftMargin
                }
            }
            binding.tvPlayerOsd.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = osdTop + safe.top
                marginStart = errorMargin + if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    safe.right
                } else {
                    safe.left
                }
                marginEnd = errorMargin + if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    safe.left
                } else {
                    safe.right
                }
            }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
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
        binding.btnPlayerSettings.setOnClickListener { showPlaybackSettings() }
        binding.btnPlayerChannelList.setOnClickListener {
            viewModel.openChannelPanel()
        }
        binding.btnPlayerRetry.setOnClickListener { viewModel.retry() }
        binding.btnPlayerErrorSwitchSource.setOnClickListener { viewModel.nextSource() }
    }

    /** 创建同页选台控制器，查询与点选经 ViewModel 执行，关闭不干预媒体实例。 */
    private fun setupChannelPanel(restoringOpenPanel: Boolean) {
        channelsPanelController = PlayerChannelsPanelController(
            binding = binding,
            restoringOpenPanel = restoringOpenPanel,
            onClose = viewModel::closeChannelPanel,
            onQueryChanged = viewModel::onChannelQueryChanged,
            onChannelClick = { channel ->
                viewModel.loadChannel(channelId = channel.id)
                viewModel.closeChannelPanel()
            },
        )
        onBackPressedDispatcher.addCallback(this, channelPanelBackCallback)
    }

    /** 选台期间不在预览上显示操作条，关闭后恢复常用按钮和原有自动隐藏节奏。 */
    private fun renderChannelPanel(state: ChannelPanelUiState) {
        val open = state.isOpen && !isInPictureInPictureMode
        val changed = channelPanelVisible != open
        val returnFocus = channelPanelVisible && binding.playerChannelsPanel.root.hasFocus()
        channelPanelVisible = open
        channelsPanelController.renderState(state.copy(isOpen = open))
        channelPanelBackCallback.isEnabled = open
        if (!changed) return
        closePlaybackSettings()
        hideChannelOsd()
        setControlsVisible(visible = !open, animate = false)
        binding.playerView.contentDescription = getString(
            if (open) R.string.player_close_channel_list else R.string.player_toggle_controls,
        )
        renderPlaybackOverlay(viewModel.uiState.value)
        if (!open && returnFocus && !binding.root.isInTouchMode) binding.btnPlayerChannelList.requestFocus()
        ViewCompat.requestApplyInsets(binding.root)
        scheduleControlsHide()
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
                launch {
                    // 查询或开关变化取消旧过滤流，隐藏及退后台期间不合成选台行。
                    viewModel.channelPanelState.collectLatest { state ->
                        renderChannelPanel(state)
                        if (state.isOpen && !isInPictureInPictureMode) {
                            viewModel.channelPanelRows(query = state.query).collect { rows ->
                                channelsPanelController.renderRows(
                                    rows = rows,
                                    query = state.query,
                                    catalogEmpty = viewModel.allChannels.value.isEmpty(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 幂等渲染：由 UiState 驱动顶栏、操作条与浮层；播放 / 清晰度请求变化才推进对应标记。 */
    private fun render(state: PlayerUiState) {
        renderHeader(state)
        renderSourceControls(state)
        renderSettings(state)
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
        val favoriteAction = getString(
            if (state.isFavorite) R.string.favorite_remove else R.string.favorite_add,
        )
        binding.btnPlayerFavorite.contentDescription = if (channel != null) {
            getString(
                R.string.channel_favorite_description_format,
                channel.name,
                favoriteAction,
            )
        } else {
            favoriteAction
        }
        binding.btnPlayerFavorite.isEnabled = channel != null
        binding.btnPlayerFavorite.alpha = if (channel != null) 1f else DISABLED_CONTROL_ALPHA
    }

    /** 错误浮层保留一步换源，正常播放时线路选择收进设置菜单。 */
    private fun renderSourceControls(state: PlayerUiState) {
        val canSwitchSource = state.sources.size >= 2
        binding.btnPlayerErrorSwitchSource.isVisible = canSwitchSource
        if (!canSwitchSource) return
        val sourceText = getString(
            R.string.switch_source_format,
            state.currentSourceIndex + 1,
            state.sources.size,
        )
        binding.btnPlayerErrorSwitchSource.text = sourceText
    }

    /** 只保留有实际动作的设置入口，已打开菜单的媒体快照失效后立即关闭。 */
    private fun renderSettings(state: PlayerUiState) {
        val hasSettings = state.sources.size >= 2 || state.qualityOptions.size >= 2 ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        binding.btnPlayerSettings.isEnabled = hasSettings
        binding.btnPlayerSettings.alpha = if (hasSettings) 1f else DISABLED_CONTROL_ALPHA
        settingsSnapshot?.let { snapshot ->
            if (snapshot.playRequestId != state.playRequestId ||
                snapshot.qualityRequestId != state.qualityRequestId ||
                snapshot.qualityOptions != state.qualityOptions
            ) {
                closePlaybackSettings()
            }
        }
    }

    /** 当前画质模式的本地化标签，同时用于菜单标题和已选项。 */
    private fun qualityLabel(state: PlayerUiState): String = when (state.qualityMode) {
        QualityMode.AUTO -> getString(R.string.quality_auto)
        QualityMode.HIGHEST -> getString(R.string.quality_highest)
        QualityMode.PINNED -> formatHeightLabel(state.pinnedVideoHeight)
    }

    /**
     * 边缘弹出菜单按需提供线路、画质和画中画，不在播放画面上常驻次要按钮。
     * 子菜单直接标记当前选项；来源和轨道变化会作废快照，不能误用旧菜单下标。
     */
    private fun showPlaybackSettings() {
        if (settingsPopup != null || isInPictureInPictureMode || channelPanelVisible) return
        val state = viewModel.uiState.value
        val popup = PopupMenu(
            ContextThemeWrapper(this, R.style.ThemeOverlay_TvWorkshop_PlayerPopup),
            binding.btnPlayerSettings,
        )
        if (state.sources.isNotEmpty()) {
            val sources = popup.menu.addSubMenu(
                getString(R.string.switch_source_format, state.currentSourceIndex + 1, state.sources.size),
            )
            sources.item.setIcon(R.drawable.ic_player_source)
            sources.item.isEnabled = state.sources.size >= 2
            state.sources.indices.forEach { index ->
                sources.add(
                    SOURCE_MENU_GROUP,
                    index,
                    Menu.NONE,
                    getString(R.string.player_source_option_format, index + 1),
                ).isChecked = index == state.currentSourceIndex
            }
            sources.setGroupCheckable(SOURCE_MENU_GROUP, true, true)
        }
        if (state.qualityOptions.size >= 2) {
            val qualities = popup.menu.addSubMenu(getString(R.string.player_quality_current_format, qualityLabel(state)))
            qualities.item.setIcon(R.drawable.ic_player_quality)
            qualities.add(QUALITY_MENU_GROUP, QUALITY_AUTO_ITEM, Menu.NONE, R.string.quality_auto)
                .isChecked = state.qualityMode == QualityMode.AUTO
            qualities.add(QUALITY_MENU_GROUP, QUALITY_HIGHEST_ITEM, Menu.NONE, R.string.quality_highest)
                .isChecked = state.qualityMode == QualityMode.HIGHEST
            state.qualityOptions.forEach { option ->
                qualities.add(QUALITY_MENU_GROUP, option.height, Menu.NONE, formatHeightLabel(option.height))
                    .isChecked = state.qualityMode == QualityMode.PINNED && state.pinnedVideoHeight == option.height
            }
            qualities.setGroupCheckable(QUALITY_MENU_GROUP, true, true)
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            popup.menu.add(Menu.NONE, PIP_MENU_ITEM, Menu.NONE, R.string.player_pip)
                .setIcon(R.drawable.ic_player_pip)
        }
        if (popup.menu.size() == 0) return
        settingsPopup = popup
        settingsSnapshot = state
        binding.root.removeCallbacks(hideControlsRunnable)
        popup.setOnMenuItemClickListener { item ->
            // 回调可能先于下一次状态渲染，执行前仍须核对媒体代次与真实轨道。
            val current = viewModel.uiState.value
            if (current.playRequestId != state.playRequestId ||
                current.qualityRequestId != state.qualityRequestId || current.qualityOptions != state.qualityOptions
            ) {
                closePlaybackSettings()
                return@setOnMenuItemClickListener true
            }
            if (item.hasSubMenu()) return@setOnMenuItemClickListener false
            when (item.groupId) {
                SOURCE_MENU_GROUP -> viewModel.selectSource(index = item.itemId, playRequestId = state.playRequestId)
                QUALITY_MENU_GROUP -> when (item.itemId) {
                    QUALITY_AUTO_ITEM -> viewModel.selectQuality(mode = QualityMode.AUTO, height = 0)
                    QUALITY_HIGHEST_ITEM -> viewModel.selectQuality(mode = QualityMode.HIGHEST, height = 0)
                    else -> viewModel.selectQuality(mode = QualityMode.PINNED, height = item.itemId)
                }
                else -> if (item.itemId == PIP_MENU_ITEM) enterPictureInPictureIfSupported()
            }
            true
        }
        popup.setOnDismissListener {
            if (settingsPopup === popup) {
                settingsPopup = null
                settingsSnapshot = null
                scheduleControlsHide()
            }
        }
        popup.show()
    }

    /** AppCompat 在窄窗口会用独立弹窗承接子菜单，关闭时一并释放，不能只 dismiss 原根菜单。 */
    private fun closePlaybackSettings() {
        val popup = settingsPopup ?: return
        for (index in 0 until popup.menu.size()) {
            popup.menu.getItem(index).subMenu?.close()
        }
        popup.dismiss()
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
        binding.playerErrorOverlay.isVisible = errorVisible && !channelPanelVisible
        binding.tvPlayerPreviewError.isVisible = errorVisible && channelPanelVisible
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

    /** 控件隐藏时才在顶部边缘短暂提示新频道，已显示的标题不再重复覆盖画面。 */
    private fun renderChannelOsd(state: PlayerUiState) {
        val channel = state.channel ?: return
        if (channel.id == lastOsdChannelId) return
        lastOsdChannelId = channel.id
        binding.tvPlayerOsd.text = channel.name
        if (controlsVisible || channelPanelVisible || isInPictureInPictureMode) {
            hideChannelOsd()
            return
        }
        binding.tvPlayerOsd.animate().cancel()
        binding.tvPlayerOsd.isVisible = true
        binding.tvPlayerOsd.animate().alpha(1f).setDuration(OSD_FADE_DURATION_MS).start()
        binding.root.removeCallbacks(osdHideRunnable)
        binding.root.postDelayed(osdHideRunnable, OSD_HIDE_DELAY_MS)
    }

    /** 显示控件或进入画中画时撤销换台提示，不让旧的结束回调覆盖后续提示。 */
    private fun hideChannelOsd() {
        binding.root.removeCallbacks(osdHideRunnable)
        binding.tvPlayerOsd.animate().cancel()
        binding.tvPlayerOsd.isVisible = false
        binding.tvPlayerOsd.alpha = 0f
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

    /** 单击预览先退出选台；正常观看时切换操作区，错误态仍保持可操作。 */
    private fun toggleControls() {
        if (channelPanelVisible) {
            viewModel.closeChannelPanel()
            return
        }
        if (errorOverlayVisible) return
        if (controlsVisible) {
            setControlsVisible(visible = false)
        } else {
            setControlsVisible(visible = true)
            scheduleControlsHide()
        }
    }

    /** 顶底栏轻移并淡出；选台立即隐藏，避免缩小后的预览被过渡中的控件遮挡。 */
    private fun setControlsVisible(visible: Boolean, animate: Boolean = true) {
        if (visible && (channelPanelVisible || isInPictureInPictureMode)) return
        val changed = controlsVisible != visible
        controlsVisible = visible
        if (visible) hideChannelOsd()
        if (!visible) {
            binding.root.removeCallbacks(hideControlsRunnable)
            // 明确隐藏时把键盘焦点还给视频，随后可用方向键或确认键重新唤出操作区。
            if (binding.playerTopBar.hasFocus() || binding.playerBottomBar.hasFocus()) {
                binding.playerView.requestFocus()
            }
        }
        for (bar in listOf(binding.playerTopBar, binding.playerBottomBar)) {
            bar.animate().cancel()
            val hiddenTranslation = resources.getDimension(R.dimen.dp_8) *
                if (bar === binding.playerTopBar) -1f else 1f
            if (!changed || !animate || !ValueAnimator.areAnimatorsEnabled()) {
                bar.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                bar.alpha = if (visible) 1f else 0f
                bar.translationY = if (visible) 0f else hiddenTranslation
                continue
            }
            if (visible) {
                bar.visibility = View.VISIBLE
                bar.animate().alpha(1f).translationY(0f).setDuration(CONTROLS_FADE_DURATION_MS).start()
            } else {
                bar.animate().alpha(0f).translationY(hiddenTranslation).setDuration(CONTROLS_FADE_DURATION_MS)
                    .withEndAction { if (!controlsVisible) bar.visibility = View.INVISIBLE }
                    .start()
            }
        }
    }

    /** 重新计时；错误、读屏、按钮焦点或其他窗口接管操作时保持操作区可用。 */
    private fun scheduleControlsHide() {
        binding.root.removeCallbacks(hideControlsRunnable)
        if (canAutoHideControls()) {
            binding.root.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
        }
    }

    /** 自动隐藏只适用于前台视频观看，不能打断键盘 / 遥控器或读屏的操作过程。 */
    private fun canAutoHideControls(): Boolean =
        controlsStarted && controlsVisible && hasWindowFocus() && !isInPictureInPictureMode &&
            !channelPanelVisible &&
            !controlsTouchInProgress && !errorOverlayVisible &&
            settingsPopup == null &&
            accessibilityManager?.isTouchExplorationEnabled != true &&
            !binding.playerTopBar.hasFocus() && !binding.playerBottomBar.hasFocus()

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

        /** 预览中的双击也只收起列表，避免关闭时额外换台。 */
        override fun onDoubleTap(motionEvent: MotionEvent): Boolean {
            if (channelPanelVisible) {
                viewModel.closeChannelPanel()
                return true
            }
            switchChannelByTapZone(motionEvent.x)
            return true
        }

        /** 选台期间隔离换台横扫，正常观看才按速度与方向执行。 */
        override fun onFling(
            down: MotionEvent?,
            up: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val horizontal = abs(velocityX)
            if (channelPanelVisible || down == null || horizontal < FLING_MIN_VELOCITY ||
                horizontal < abs(velocityY) * FLING_RATIO
            ) {
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
        private const val CONTROLS_HIDE_DELAY_MS = 3_000L

        /** 操作区显隐淡入淡出时长。 */
        private const val CONTROLS_FADE_DURATION_MS = 200L

        /** 禁用入口保留上下文，但通过透明度表达当前不可操作。 */
        private const val DISABLED_CONTROL_ALPHA = 0.5f

        /** 线路下标使用独立分组，不与视频高度或普通动作的 itemId 混淆。 */
        private const val SOURCE_MENU_GROUP = 1

        /** 画质选项身份由模式标记或真实视频高度组成。 */
        private const val QUALITY_MENU_GROUP = 2

        /** 不属于线路或画质分组的画中画动作。 */
        private const val PIP_MENU_ITEM = 1

        /** 自动档使用负值，与真实视频高度区分。 */
        private const val QUALITY_AUTO_ITEM = -1

        /** 最高档同样不占用真实高度的正值身份。 */
        private const val QUALITY_HIGHEST_ITEM = -2

        /** 这些按键只负责导航，不消费确认键，避免与视频点击的显隐动作冲突。 */
        private val CONTROL_NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_TAB,
        )

        /** 换台 OSD 停留时长。 */
        private const val OSD_HIDE_DELAY_MS = 1_500L

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
