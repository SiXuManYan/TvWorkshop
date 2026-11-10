package com.sugar.tv.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sugar.tv.R
import com.sugar.tv.TvApplication
import com.sugar.tv.data.model.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放页 ViewModel：持有频道、信号源、收藏等业务状态并协调数据仓库访问。
 *
 * ExoPlayer 实例归 [PlayerActivity] 所有；本类只决定「该播放哪一路源」（通过
 * [PlayerUiState.playRequestId] 驱动 UI 重新装载），并提供换频道 / 换源 / 重试 / 收藏等用户意图。
 * 持久化与解析全部下沉到仓库层并在 IO 调度器执行，本类不持有 View / Context（Application 除外）。
 *
 * @param application Application 级上下文，仅用于获取 [TvApplication.channelRepository]
 * @param savedStateHandle 恢复句柄：进程重建后恢复频道、信号源与选台面板的开关和查询词；
 *   频道与信号源键复用 [PlayerActivity] 的 Intent 常量（extras 作为初始值进入恢复句柄）
 */
class PlayerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    /** 应用级频道仓库，负责耗时操作的调度与持久化。 */
    private val repository = (application as TvApplication).channelRepository

    /** 播放业务状态的唯一可写入口。 */
    private val _uiState = MutableStateFlow(PlayerUiState())

    /** 播放页 UI 状态（不可变，只读暴露）。 */
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** 选台状态单独保存，输入变化不触发整个播放状态的重新渲染。 */
    private val _channelPanelState = MutableStateFlow(
        ChannelPanelUiState(
            isOpen = savedStateHandle[CHANNEL_PANEL_OPEN] ?: false,
            query = savedStateHandle[CHANNEL_PANEL_QUERY] ?: "",
        ),
    )

    /** 频道面板开关与输入，跨前后台和进程重建恢复。 */
    val channelPanelState: StateFlow<ChannelPanelUiState> = _channelPanelState.asStateFlow()

    /** 仓库频道全集快照，供换台和面板过滤共用。 */
    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())

    /** 全部频道（按仓库全集顺序），供频道面板展示与上 / 下频道切换。 */
    val allChannels: StateFlow<List<Channel>> = _allChannels.asStateFlow()

    /** 收藏操作成功的一次性提示通道。 */
    private val _favoriteNotice = EventChannel<Int>(capacity = EventChannel.BUFFERED)

    /** 收藏变化的一次性提示，值为字符串资源 id（favorite_added / favorite_removed）。 */
    val favoriteNotice: Flow<Int> = _favoriteNotice.receiveAsFlow()

    /** 收藏频道 id 快照，用于换频道后恢复收藏态。 */
    private var favoriteIdSet: Set<String> = emptySet()

    /** 当前待播放 / 已播放的频道 id：连续换台以最新请求为基准，不等待上一次查询完成。 */
    private val currentChannelId: String?
        get() = latestRequestedChannelId ?: _uiState.value.channel?.id
            ?: savedStateHandle.get<String>(PlayerActivity.EXTRA_CHANNEL_ID)

    /** 当前频道加载任务：新加载启动前取消上一次，避免快速换频道时旧结果乱序写回。 */
    private var loadJob: Job? = null

    /** 最新一次请求加载的频道 id：加载结果写回状态前核对，旧结果不得覆盖最新选择。 */
    private var latestRequestedChannelId: String? = null

    /** 末路失败后的有限重试任务；手动重试、换源或换频道取消，收藏等操作不取消。 */
    private var autoRetryJob: Job? = null

    /** 当前「全部失败」回合内已执行的自动重试次数，换频道或手动重试时归零。 */
    private var autoRetryAttempts = 0

    /** 已为直播窗口落后执行过原线路恢复的请求，避免损坏的播放列表无限重接。 */
    private var liveWindowRecoveryRequestId: Long? = null

    /** 观看选项与媒体状态分离，SavedStateHandle 仅保存枚举和时间戳。 */
    private val _viewingOptions = MutableStateFlow(
        PlayerViewingOptions(
            displayMode = VideoDisplayMode.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(KEY_DISPLAY_MODE)
            } ?: VideoDisplayMode.FIT,
        ),
    )

    /** 当前会话的显示模式和定时截止值；跨频道保留，退出播放页结束。 */
    val viewingOptions: StateFlow<PlayerViewingOptions> = _viewingOptions.asStateFlow()

    init {
        restoreSleepTimer()
        // 加载任务会立即读写上述字段，声明先于 init 以明确初始化依赖。
        viewModelScope.launch {
            repository.observeAllChannels().collect { channels ->
                _allChannels.value = channels
            }
        }
        viewModelScope.launch {
            repository.observeFavoriteIds()
                .map { it.toSet() }
                .flowOn(Dispatchers.Default)
                .collect { ids ->
                    favoriteIdSet = ids
                    val playingId = _uiState.value.channel?.id ?: return@collect
                    _uiState.update { state ->
                        val favorite = favoriteIdSet.contains(playingId)
                        if (state.isFavorite == favorite) state else state.copy(isFavorite = favorite)
                    }
                }
        }
        // Intent extras 会作为初始值进入 SavedStateHandle；进程重建时取写入后的最新值
        val requestedChannelId = savedStateHandle.get<String>(PlayerActivity.EXTRA_CHANNEL_ID)
        if (requestedChannelId != null) {
            loadChannel(requestedChannelId)
        } else {
            _uiState.update { it.copy(channelMissing = true) }
        }
    }

    /**
     * 加载并开始播放指定频道：查询频道、恢复收藏态并记录一次观看。
     *
     * 快速连续换频道（上一 / 下一、面板点选并发）时，先取消上一次未完成的加载任务，
     * 查询返回后再核对「是否仍是最新请求」：两者共同保证只有最后选择的频道会写入
     * UI 状态与恢复句柄，旧频道结果不会覆盖用户最后的选择。
     *
     * 首次进入按 Intent / 恢复值选择起始信号源；同一频道重复加载（如面板再次点选当前频道）
     * 保持当前源且不重新装载播放请求；切换到新频道则从第一路信号源开始。
     */
    fun loadChannel(channelId: String) {
        if (_viewingOptions.value.sleepTimerExpired) return
        latestRequestedChannelId = channelId
        autoRetryJob?.cancel()
        autoRetryAttempts = 0
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val channel = try {
                repository.channelById(channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 查询失败使用已有错误浮层承接，避免未处理的数据库异常终止页面。
                null
            }
            // 结果代次核对：已被更新的请求取代时丢弃本次结果（任务通常已被取消，此为兜底）
            if (latestRequestedChannelId != channelId) return@launch
            savedStateHandle[PlayerActivity.EXTRA_CHANNEL_ID] = channelId
            if (channel == null) {
                _uiState.update {
                    it.copy(
                        channel = null,
                        sources = emptyList(),
                        channelMissing = true,
                        allSourcesFailed = false,
                        isBuffering = false,
                        isFavorite = false,
                        qualityOptions = emptyList(),
                        playRequestId = it.playRequestId + 1,
                    )
                }
                return@launch
            }
            val startIndex = resolveStartIndex(channel)
            _uiState.update { current ->
                val sameRequest = current.channel?.id == channel.id &&
                    current.currentSourceIndex == startIndex &&
                    current.sources.isNotEmpty() &&
                    !current.allSourcesFailed
                current.copy(
                    channel = channel,
                    sources = channel.sources,
                    currentSourceIndex = startIndex,
                    isFavorite = favoriteIdSet.contains(channel.id),
                    channelMissing = false,
                    allSourcesFailed = false,
                    isBuffering = if (sameRequest) current.isBuffering else channel.sources.isNotEmpty(),
                    qualityOptions = if (sameRequest) current.qualityOptions else emptyList(),
                    playRequestId = if (sameRequest) current.playRequestId else current.playRequestId + 1,
                )
            }
            // 观看记录跟随核对后的结果：被取代的旧加载不再落库
            try {
                repository.recordRecent(channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 观看记录是辅助数据，写入失败不应中断已开始的直播。
            }
        }
    }

    /** 切换到全集顺序中的下一个频道（末尾循环回第一个）。 */
    fun nextChannel() = switchChannel(offset = 1)

    /** 切换到全集顺序中的上一个频道（开头循环回最后一个）。 */
    fun prevChannel() = switchChannel(offset = -1)

    /** 手动切换到下一路信号源（循环换源），可从「全部失败」错误态恢复播放。 */
    fun nextSource() {
        val current = _uiState.value
        val size = current.sources.size
        if (size < 2) return
        selectSource(index = (current.currentSourceIndex + 1).mod(size), playRequestId = current.playRequestId)
    }

    /** 直接选择菜单中的线路；拒绝过期媒体请求，重复选择正常线路不重新缓冲。 */
    fun selectSource(index: Int, playRequestId: Long) {
        val current = _uiState.value
        if (current.playRequestId != playRequestId || index !in current.sources.indices) return
        if (index == current.currentSourceIndex && !current.allSourcesFailed) return
        autoRetryJob?.cancel()
        // 手动选源延续本回合的重试次数，与原 nextSource 的取消和恢复边界一致。
        savedStateHandle[PlayerActivity.EXTRA_SOURCE_INDEX] = index
        _uiState.update {
            it.copy(
                currentSourceIndex = index,
                allSourcesFailed = false,
                isBuffering = true,
                qualityOptions = emptyList(),
                playRequestId = it.playRequestId + 1,
            )
        }
    }

    /**
     * 当前媒体报告致命错误：直播窗口落后先在原线路重新接入一次，其他错误或恢复再次失败
     * 才尝试下一路；末路失败进入错误态并有限重试，等待期间仍可手动操作。
     *
     * @param playRequestId 事件所属的媒体请求，拒绝旧媒体的迟到错误
     * @param isBehindLiveWindow 是否已落到直播播放列表保留窗口之外
     */
    fun onPlaybackError(playRequestId: Long, isBehindLiveWindow: Boolean) {
        if (playRequestId != _uiState.value.playRequestId || _uiState.value.sources.isEmpty()) return
        autoRetryJob?.cancel()
        val current = _uiState.value
        if (isBehindLiveWindow && liveWindowRecoveryRequestId != playRequestId) {
            val recoveryRequestId = current.playRequestId + 1
            liveWindowRecoveryRequestId = recoveryRequestId
            // 新 MediaItem 从源的默认直播位置接入；同一恢复请求再次落后时交回正常换源流程。
            _uiState.update {
                it.copy(
                    allSourcesFailed = false,
                    isBuffering = true,
                    qualityOptions = emptyList(),
                    playRequestId = recoveryRequestId,
                )
            }
            return
        }
        val nextIndex = current.currentSourceIndex + 1
        if (nextIndex < current.sources.size) {
            savedStateHandle[PlayerActivity.EXTRA_SOURCE_INDEX] = nextIndex
            _uiState.update {
                it.copy(
                    currentSourceIndex = nextIndex,
                    allSourcesFailed = false,
                    isBuffering = true,
                    qualityOptions = emptyList(),
                    playRequestId = it.playRequestId + 1,
                )
            }
        } else {
            _uiState.update { it.copy(allSourcesFailed = true, isBuffering = false) }
            scheduleAutoRetry()
        }
    }

    /**
     * 「全部失败」后的有限自动重试：延迟 [AUTO_RETRY_DELAY_MS] 后仍处于错误态时，
     * 重新装载当前信号源一次（网络抖动 / 源短暂不可达的自愈）；最多 [MAX_AUTO_RETRY]
     * 次，避免对确定失效的频道无限轮询耗电。手动重试、换源、换频道取消待执行任务；
     * 当前只有手动重试与换频道归零计数，手动换源保留计数。
     */
    private fun scheduleAutoRetry() {
        if (autoRetryAttempts >= MAX_AUTO_RETRY) {
            return
        }
        autoRetryAttempts++
        autoRetryJob = viewModelScope.launch {
            delay(AUTO_RETRY_DELAY_MS)
            if (_uiState.value.allSourcesFailed) {
                _uiState.update {
                    it.copy(
                        allSourcesFailed = false,
                        isBuffering = true,
                        qualityOptions = emptyList(),
                        playRequestId = it.playRequestId + 1,
                    )
                }
            }
        }
    }

    /** 错误浮层「重试」：频道缺失或无信号源时重新查询频道，否则保持下标重试当前信号源。 */
    fun retry() {
        if (_viewingOptions.value.sleepTimerExpired) return
        autoRetryJob?.cancel()
        autoRetryAttempts = 0
        val current = _uiState.value
        if (current.channel == null || current.sources.isEmpty()) {
            currentChannelId?.let(::loadChannel)
        } else {
            _uiState.update {
                it.copy(
                    allSourcesFailed = false,
                    isBuffering = true,
                    qualityOptions = emptyList(),
                    playRequestId = it.playRequestId + 1,
                )
            }
        }
    }

    /** 同步当前播放请求的缓冲状态，忽略换台后旧媒体的迟到回调。 */
    fun onBufferingChanged(playRequestId: Long, isBuffering: Boolean) {
        _uiState.update {
            if (it.playRequestId != playRequestId || it.isBuffering == isBuffering) it
            else it.copy(isBuffering = isBuffering)
        }
    }

    /**
     * 同步播放器上报的视频档位（由 `onTracksChanged` 驱动）。
     *
     * 档位与上次一致时不重复发射；已选高度在新频道不存在时自动回落 [QualityMode.AUTO]，
     * 保证锁定偏好跨频道生效且不会选中不存在的档位。
     */
    fun onVideoQualityOptionsChanged(playRequestId: Long, options: List<QualityOption>) {
        _uiState.update { state ->
            if (state.playRequestId != playRequestId) return@update state
            val keepPinned = state.qualityMode != QualityMode.PINNED ||
                options.any { it.height == state.pinnedVideoHeight }
            if (state.qualityOptions == options && keepPinned) {
                state
            } else {
                state.copy(
                    qualityOptions = options,
                    qualityMode = if (keepPinned) state.qualityMode else QualityMode.AUTO,
                    pinnedVideoHeight = if (keepPinned) state.pinnedVideoHeight else 0,
                    // 回落自动档也必须驱动播放器解除旧的高度限制，不能只更新按钮文案。
                    qualityRequestId = if (keepPinned) state.qualityRequestId else state.qualityRequestId + 1,
                )
            }
        }
    }

    /**
     * 用户切换清晰度：更新模式与锁定高度并递增 [PlayerUiState.qualityRequestId]，
     * UI 侧据此把新的 `TrackSelectionParameters` 应用到播放器（实际生效由播放器侧完成）。
     */
    fun selectQuality(mode: QualityMode, height: Int) {
        _uiState.update { state ->
            // 对话框打开期间可能已经换源，不接受旧列表中已失效的高度。
            if (mode == QualityMode.PINNED && state.qualityOptions.none { it.height == height }) {
                return@update state
            }
            if (state.qualityMode == mode && state.pinnedVideoHeight == height) {
                state
            } else {
                state.copy(
                    qualityMode = mode,
                    pinnedVideoHeight = height,
                    qualityRequestId = state.qualityRequestId + 1,
                )
            }
        }
    }

    /**
     * 切换当前频道收藏状态，完成后通过一次性事件提示结果（文案由 UI 侧解析资源）。
     *
     * 仓库切换为单条事务（见 [repository.toggleFavorite]），并发双按不丢切换；
     * UI 星标由仓库观察流同步，操作完成只发提示，避免较早请求的返回值覆盖较新的数据库状态。
     */
    fun toggleFavorite() {
        val channelId = _uiState.value.channel?.id ?: return
        viewModelScope.launch {
            try {
                val nowFavorite = repository.toggleFavorite(channelId)
                _favoriteNotice.trySend(if (nowFavorite) R.string.favorite_added else R.string.favorite_removed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 写入失败保持仓库真实收藏态，不影响播放。
            }
        }
    }

    /** 每次主动打开从当前频道开始浏览；已打开或前后台恢复不清空查询。 */
    fun openChannelPanel() {
        if (_channelPanelState.value.isOpen) return
        savedStateHandle[CHANNEL_PANEL_OPEN] = true
        savedStateHandle[CHANNEL_PANEL_QUERY] = ""
        _channelPanelState.update { it.copy(isOpen = true, query = "") }
    }

    /** 收起面板，只改变选台 UI，不重新装载当前媒体。 */
    fun closeChannelPanel() {
        savedStateHandle[CHANNEL_PANEL_OPEN] = false
        _channelPanelState.update { it.copy(isOpen = false) }
    }

    /** 输入原文进入恢复句柄；过滤流在后台统一处理空白与大小写。 */
    fun onChannelQueryChanged(query: String) {
        if (_channelPanelState.value.query == query) return
        savedStateHandle[CHANNEL_PANEL_QUERY] = query
        _channelPanelState.update { it.copy(query = query) }
    }

    /** 画面模式只驱动 View 缩放，不重新 prepare 当前直播。 */
    fun selectDisplayMode(mode: VideoDisplayMode) {
        savedStateHandle[KEY_DISPLAY_MODE] = mode.name
        _viewingOptions.update { it.copy(displayMode = mode) }
    }

    /** 设置或取消当前会话的定时；重新选择同一时长也从当前时刻重新计时。 */
    fun setSleepTimer(minutes: Int) {
        if (minutes != 0 && minutes !in SleepTimer.durationsMinutes) return
        val duration = minutes * SleepTimer.MILLIS_PER_MINUTE
        savedStateHandle[KEY_SLEEP_DEADLINE] = if (minutes == 0) 0L else System.currentTimeMillis() + duration
        savedStateHandle[KEY_SLEEP_DURATION] = minutes
        savedStateHandle[KEY_SLEEP_EXPIRED] = false
        _viewingOptions.update {
            it.copy(
                sleepDeadlineElapsedMs = if (minutes == 0) 0L else SystemClock.elapsedRealtime() + duration,
                sleepDurationMinutes = minutes,
                sleepTimerExpired = false,
            )
        }
    }

    /** 页面在起播前与计时回调中核对单调时钟，过期后取消重试和未完成频道加载。 */
    fun refreshSleepTimer() {
        val deadline = _viewingOptions.value.sleepDeadlineElapsedMs
        if (deadline == 0L || SystemClock.elapsedRealtime() < deadline) return
        autoRetryJob?.cancel()
        loadJob?.cancel()
        savedStateHandle[KEY_SLEEP_EXPIRED] = true
        _viewingOptions.update { it.copy(sleepDeadlineElapsedMs = 0L, sleepTimerExpired = true) }
    }

    /** 恢复时将墙上时钟的截止值转为本进程单调时钟，已到期状态优先于媒体加载。 */
    private fun restoreSleepTimer() {
        val deadline = savedStateHandle.get<Long>(KEY_SLEEP_DEADLINE) ?: 0L
        val wasExpired = savedStateHandle.get<Boolean>(KEY_SLEEP_EXPIRED) == true
        if (deadline == 0L && !wasExpired) return
        val remaining = SleepTimer.restoreRemainingMillis(
            deadlineEpochMs = deadline,
            nowEpochMs = System.currentTimeMillis(),
        )
        val expired = wasExpired || remaining == 0L
        savedStateHandle[KEY_SLEEP_EXPIRED] = expired
        _viewingOptions.update {
            it.copy(
                sleepDeadlineElapsedMs = if (expired) 0L else SystemClock.elapsedRealtime() + remaining,
                sleepDurationMinutes = savedStateHandle[KEY_SLEEP_DURATION] ?: 0,
                sleepTimerExpired = expired,
            )
        }
    }

    /** 仅由可见面板收集；查询变化时 UI 取消旧流，避免隐藏期间持续构造列表。 */
    fun channelPanelRows(query: String): Flow<List<ChannelPanelRow>> {
        val search = query.trim()
        return combine(
            allChannels,
            uiState.map { it.channel?.id }.distinctUntilChanged(),
        ) { channels, playingId ->
            channels.asSequence()
                .filter { search.isEmpty() || it.name.contains(search, ignoreCase = true) }
                .map { ChannelPanelRow(channel = it, isPlaying = it.id == playingId) }
                .toList()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    override fun onCleared() {
        // ViewModel 销毁时关闭一次性事件通道，缓冲中的提示仍可被收集方消费完
        _favoriteNotice.close()
        super.onCleared()
    }

    /** 解析该频道应从第几路信号源开始播放，并同步写入恢复句柄。 */
    private fun resolveStartIndex(channel: Channel): Int {
        val maxIndex = (channel.sources.size - 1).coerceAtLeast(0)
        val current = _uiState.value
        val requested = when {
            current.channel == null -> savedStateHandle.get<Int>(PlayerActivity.EXTRA_SOURCE_INDEX) ?: 0
            current.channel?.id == channel.id -> current.currentSourceIndex
            else -> 0 // 换到新频道时从第一路信号源开始
        }
        val startIndex = requested.coerceIn(0, maxIndex)
        savedStateHandle[PlayerActivity.EXTRA_SOURCE_INDEX] = startIndex
        return startIndex
    }

    /** 按全集顺序与固定偏移切换频道，越界时循环回绕；当前频道不在全集时保持不动。 */
    private fun switchChannel(offset: Int) {
        val channels = _allChannels.value
        if (channels.isEmpty()) return
        val playingId = currentChannelId ?: return
        val index = channels.indexOfFirst { it.id == playingId }
        if (index < 0) return
        loadChannel(channels[(index + offset).mod(channels.size)].id)
    }

    private companion object {
        /** 显示模式只在当前播放页会话中恢复。 */
        const val KEY_DISPLAY_MODE = "player_display_mode"

        /** 墙上时钟截止值供进程重建恢复，正常运行使用 elapsedRealtime。 */
        const val KEY_SLEEP_DEADLINE = "player_sleep_deadline_epoch"

        /** 菜单保留用户选中的时长。 */
        const val KEY_SLEEP_DURATION = "player_sleep_duration_minutes"

        /** 已到期不能因为配置变化、后台恢复或进程重建而再次起播。 */
        const val KEY_SLEEP_EXPIRED = "player_sleep_expired"

        /** 选台开关和查询分别存入恢复句柄，不保存频道对象或整个列表。 */
        const val CHANNEL_PANEL_OPEN = "channel_panel_open"

        /** 保留用户尚未完成的输入，面板主动重新打开时再清空。 */
        const val CHANNEL_PANEL_QUERY = "channel_panel_query"

        /** 「全部失败」后自动重试的延迟（毫秒）：给网络抖动留出恢复窗口。 */
        const val AUTO_RETRY_DELAY_MS = 8_000L

        /** 「全部失败」后单回合自动重试上限，超出后仅等待用户手动操作。 */
        const val MAX_AUTO_RETRY = 2
    }
}
