package com.sugar.tv.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sugar.tv.R
import com.sugar.tv.TvApplication
import com.sugar.tv.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页 ViewModel：持有频道、信号源、收藏等业务状态并协调数据仓库访问。
 *
 * ExoPlayer 实例归 [PlayerActivity] 所有；本类只决定「该播放哪一路源」（通过
 * [PlayerUiState.playRequestId] 驱动 UI 重新装载），并提供换频道 / 换源 / 重试 / 收藏等用户意图。
 * 持久化与解析全部下沉到仓库层并在 IO 调度器执行，本类不持有 View / Context（Application 除外）。
 *
 * @param application Application 级上下文，仅用于获取 [TvApplication.channelRepository]
 * @param savedStateHandle 恢复句柄：进程重建后恢复频道 id 与上次选择的信号源下标；
 *   键复用 [PlayerActivity] 的 Intent 常量（Intent extras 会作为初始值进入 SavedStateHandle）
 */
class PlayerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as TvApplication).channelRepository

    private val _uiState = MutableStateFlow(PlayerUiState())

    /** 播放页 UI 状态（不可变，只读暴露）。 */
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())

    /** 全部频道（按仓库全集顺序），供频道抽屉展示与上 / 下频道切换。 */
    val allChannels: StateFlow<List<Channel>> = _allChannels.asStateFlow()

    private val _favoriteNotice = EventChannel<Int>(capacity = EventChannel.BUFFERED)

    /** 收藏变化的一次性提示，值为字符串资源 id（favorite_added / favorite_removed）。 */
    val favoriteNotice: Flow<Int> = _favoriteNotice.receiveAsFlow()

    /** 收藏频道 id 快照，用于换频道后恢复收藏态。 */
    private var favoriteIdSet: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            repository.observeAllChannels().flowOn(Dispatchers.IO).collect { channels ->
                _allChannels.value = channels
            }
        }
        viewModelScope.launch {
            repository.observeFavoriteIds().flowOn(Dispatchers.IO).collect { ids ->
                favoriteIdSet = ids.toSet()
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

    /** 当前待播放 / 已播放的频道 id：优先取状态中的频道，缺失时回退到恢复句柄。 */
    private val currentChannelId: String?
        get() = _uiState.value.channel?.id ?: savedStateHandle.get<String>(PlayerActivity.EXTRA_CHANNEL_ID)

    /** 当前频道加载任务：新加载启动前取消上一次，避免快速换频道时旧结果乱序写回。 */
    private var loadJob: Job? = null

    /** 最新一次请求加载的频道 id（代次标记）：加载结果写回状态前核对，旧结果不得覆盖最新选择。 */
    private var latestRequestedChannelId: String? = null

    /** 「全部失败」后的自动重试任务：有限次数，任何手动操作（重试 / 换源 / 换频道）取消。 */
    private var autoRetryJob: Job? = null

    /** 当前「全部失败」回合内已执行的自动重试次数，换频道或手动重试时归零。 */
    private var autoRetryAttempts = 0

    /**
     * 加载并开始播放指定频道：查询频道、恢复收藏态并记录一次观看。
     *
     * 快速连续换频道（上一 / 下一、抽屉点选并发）时，先取消上一次未完成的加载任务，
     * 查询返回后再核对「是否仍是最新请求」：两者共同保证只有最后选择的频道会写入
     * UI 状态与恢复句柄，旧频道结果不会覆盖用户最后的选择。
     *
     * 首次进入按 Intent / 恢复值选择起始信号源；同一频道重复加载（如抽屉再次点选当前频道）
     * 保持当前源且不重新装载播放请求；切换到新频道则从第一路信号源开始。
     */
    fun loadChannel(channelId: String) {
        latestRequestedChannelId = channelId
        autoRetryJob?.cancel()
        autoRetryAttempts = 0
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) { repository.channelById(channelId) }
            // 结果代次核对：已被更新的请求取代时丢弃本次结果（任务通常已被取消，此为兜底）
            if (latestRequestedChannelId != channelId) return@launch
            savedStateHandle[PlayerActivity.EXTRA_CHANNEL_ID] = channelId
            if (channel == null) {
                _uiState.update {
                    it.copy(
                        channel = null,
                        sources = emptyList(),
                        channelMissing = true,
                        isBuffering = false,
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
                    playRequestId = if (sameRequest) current.playRequestId else current.playRequestId + 1,
                )
            }
            // 观看记录跟随核对后的结果：被取代的旧加载不再落库
            withContext(Dispatchers.IO) { repository.recordRecent(channelId) }
        }
    }

    /** 切换到全集顺序中的下一个频道（末尾循环回第一个）。 */
    fun nextChannel() = switchChannel(offset = 1)

    /** 切换到全集顺序中的上一个频道（开头循环回最后一个）。 */
    fun prevChannel() = switchChannel(offset = -1)

    /** 手动切换到下一路信号源（循环换源），可从「全部失败」错误态恢复播放。 */
    fun nextSource() {
        autoRetryJob?.cancel()
        val current = _uiState.value
        val size = current.sources.size
        if (size < 2) return
        val nextIndex = (current.currentSourceIndex + 1).mod(size)
        savedStateHandle[PlayerActivity.EXTRA_SOURCE_INDEX] = nextIndex
        _uiState.update {
            it.copy(
                currentSourceIndex = nextIndex,
                allSourcesFailed = false,
                isBuffering = true,
                playRequestId = it.playRequestId + 1,
            )
        }
    }

    /**
     * 当前信号源播放失败（由播放器 `Player.Listener` 回调驱动）：自动切到下一路；
     * 已到最后一路则停在「全部失败」错误态，并在有限次数内自动重试第一路
     * （见 [scheduleAutoRetry]），等待期间用户随时可以重试或手动换源。
     */
    fun onPlaybackError() {
        autoRetryJob?.cancel()
        val current = _uiState.value
        val nextIndex = current.currentSourceIndex + 1
        if (nextIndex < current.sources.size) {
            savedStateHandle[PlayerActivity.EXTRA_SOURCE_INDEX] = nextIndex
            _uiState.update {
                it.copy(
                    currentSourceIndex = nextIndex,
                    allSourcesFailed = false,
                    isBuffering = true,
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
     * 次，避免对确定失效的频道无限轮询耗电。用户的手动重试、换源、换频道都会取消
     * 待执行的重试并把计数归零。
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
                        playRequestId = it.playRequestId + 1,
                    )
                }
            }
        }
    }

    /** 错误浮层「重试」：频道缺失或无信号源时重新查询频道，否则保持下标重试当前信号源。 */
    fun retry() {
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
                    playRequestId = it.playRequestId + 1,
                )
            }
        }
    }

    /** 同步播放器缓冲状态到 UI（由播放器状态回调驱动，UI 据此显隐中央加载指示）。 */
    fun onBufferingChanged(isBuffering: Boolean) {
        _uiState.update { if (it.isBuffering == isBuffering) it else it.copy(isBuffering = isBuffering) }
    }

    /**
     * 同步播放器上报的视频档位（由 `onTracksChanged` 驱动）。
     *
     * 档位与上次一致时不重复发射；前置顶高度在新频道不存在时自动回落 [QualityMode.AUTO]，
     * 保证锁定偏好跨频道生效且不会选中不存在的档位。
     */
    fun onVideoQualityOptionsChanged(options: List<QualityOption>) {
        _uiState.update { state ->
            if (state.qualityOptions == options) {
                state
            } else {
                val keepPinned = state.qualityMode != QualityMode.PINNED ||
                    options.any { it.height == state.pinnedVideoHeight }
                state.copy(
                    qualityOptions = options,
                    qualityMode = if (keepPinned) state.qualityMode else QualityMode.AUTO,
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
     * UI 星标与提示以仓库实际落点（返回值）为准，而非调用前的旧快照，
     * 快速连续按收藏键时不会出现 UI / 提示与库内状态错位。
     */
    fun toggleFavorite() {
        val channelId = _uiState.value.channel?.id ?: return
        viewModelScope.launch {
            val nowFavorite = withContext(Dispatchers.IO) { repository.toggleFavorite(channelId) }
            _uiState.update { state ->
                if (state.channel?.id == channelId && state.isFavorite != nowFavorite) {
                    state.copy(isFavorite = nowFavorite)
                } else {
                    state
                }
            }
            _favoriteNotice.trySend(if (nowFavorite) R.string.favorite_added else R.string.favorite_removed)
        }
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
        val playingId = _uiState.value.channel?.id ?: return
        val index = channels.indexOfFirst { it.id == playingId }
        if (index < 0) return
        loadChannel(channels[(index + offset).mod(channels.size)].id)
    }

    private companion object {
        /** 「全部失败」后自动重试的延迟（毫秒）：给网络抖动留出恢复窗口。 */
        const val AUTO_RETRY_DELAY_MS = 8_000L

        /** 「全部失败」后单回合自动重试上限，超出后仅等待用户手动操作。 */
        const val MAX_AUTO_RETRY = 2
    }
}
