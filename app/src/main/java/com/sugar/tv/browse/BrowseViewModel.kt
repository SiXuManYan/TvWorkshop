package com.sugar.tv.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sugar.tv.R
import com.sugar.tv.TvApplication
import com.sugar.tv.data.model.Channel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 频道浏览页 ViewModel：持有分类、搜索、频道列表与收藏的全部业务状态。
 *
 * 数据访问全部委托 [TvApplication.channelRepository]；Repository 的耗时操作自带 IO 调度器，
 * 本类只做流的组合与状态合成，不持有任何 View / Activity / Dialog 引用。
 *
 * 进程重建恢复：经 [savedStateHandle] 回填选中分类与已生效搜索词（均为小型可序列化数据），
 * 与播放页 PlayerViewModel 的恢复策略一致，满足「连续且可恢复的交互」。
 *
 * @property application Application 级上下文，仅用于获取应用级依赖（频道数据仓库）
 * @param savedStateHandle 恢复句柄：持久化选中分类（枚举名）与已生效搜索词
 */
class BrowseViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    /** 频道数据仓库：应用级单例入口，按数据契约面向接口编程。 */
    private val repository = (application as TvApplication).channelRepository

    /** 当前选中分类流：Chip 单选结果。 */
    private val categoryFlow = MutableStateFlow(BrowseCategory.CCTV)

    /**
     * 已生效（去抖后）的搜索词流。
     *
     * 去抖实现在写入侧完成（见 [onQueryChanged]）：非空输入连续变化时延迟 250ms 提交，
     * 清空立即生效，初始空查询不等待去抖。这样 combine 的首次发射不被去抖延迟阻塞，
     * 满足「loading 在首数据到达即结束」的状态契约。
     */
    private val queryFlow = MutableStateFlow("")

    /**
     * 收藏乐观更新覆盖表：channelId → 带请求代次的目标收藏状态。
     *
     * 仓库流未反映最新写入前，以覆盖值优先渲染星标；失败或成功确认后移除对应条目。
     */
    private val favoriteOverrides = MutableStateFlow<Map<String, FavoriteOverride>>(emptyMap())

    /** 收藏点击代次：较早任务完成时不能释放后续点击创建的覆盖。 */
    private var favoriteRequestId = 0L

    /** 按点击顺序提交收藏写入，锁仅覆盖数据库操作，不包含等待观察流同步的时间。 */
    private val favoriteWriteMutex = Mutex()

    init {
        // 进程重建恢复：回填选中分类（枚举名失效时回退默认）与已生效搜索词
        val restoredCategory = savedStateHandle.get<String>(KEY_CATEGORY)
            ?.let { name -> BrowseCategory.entries.firstOrNull { it.name == name } }
        if (restoredCategory != null) {
            categoryFlow.value = restoredCategory
        }
        queryFlow.value = savedStateHandle.get<String>(KEY_QUERY).orEmpty()
    }

    /** 导入 / 清空等订阅任务是否进行中：用于禁用对话框按钮并显示进度。 */
    private val _importInFlight = MutableStateFlow(false)

    /** 只读暴露的订阅任务进行中状态。 */
    val importInFlight: StateFlow<Boolean> = _importInFlight.asStateFlow()

    /** 一次性提示事件通道：导入结果、收藏提示、清空结果。 */
    private val _events = EventChannel<BrowseEvent>(capacity = EventChannel.BUFFERED)

    /** 只读事件接收端；由页面 repeatOnLifecycle(STARTED) 收集。 */
    val events: ReceiveChannel<BrowseEvent> = _events

    /** 搜索词写入去抖任务：连续输入时取消上一次延迟提交。 */
    private var searchDebounceJob: Job? = null

    /** 频道全集变化时才重建 id 索引，避免每次收藏或观看记录更新都遍历整个目录。 */
    private val channelCatalog = repository.observeAllChannels().map { channels ->
        ChannelCatalog(channels = channels, channelsById = channels.associateBy { it.id })
    }

    /**
     * 数据快照流：频道列表 × 收藏 id × 最近观看 id × 乐观收藏覆盖合并。
     *
     * 收藏以「仓库值 + 覆盖值」合成：仓库已反映目标状态的覆盖条目自动失效，
     * 保证释放覆盖（从表中移除）不会引起星标回闪。
     */
    private val channelSnapshot: Flow<ChannelSnapshot> = combine(
        channelCatalog,
        repository.observeFavoriteIds(),
        repository.observeRecentIds(),
        favoriteOverrides,
    ) { catalog, favoriteIds, recentIds, overrides ->
        ChannelSnapshot(
            channels = catalog.channels,
            channelsById = catalog.channelsById,
            favoriteIds = effectiveFavorites(favoriteIds, overrides),
            recentIds = recentIds,
        )
    }

    /**
     * 浏览页 UI 状态流：快照 × 已生效搜索词 × 分类 合成，
     * 首次发射（首数据到达）即结束 loading；订阅暂停 5 秒以上自动停采上游。
     */
    val uiState: StateFlow<BrowseUiState> = combine(
        channelSnapshot,
        queryFlow,
        categoryFlow,
    ) { snapshot, query, category ->
        buildUiState(snapshot, query, category)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        // 菜单创建可能早于 Room 首次发射，初始状态也需带恢复词才能正确展开搜索框。
        initialValue = BrowseUiState(selectedCategory = categoryFlow.value, query = queryFlow.value),
    )

    /**
     * 切换分类（Chip 单选回调）；同步写入恢复句柄，进程重建后回到该分类。
     */
    fun onCategorySelected(category: BrowseCategory) {
        categoryFlow.value = category
        savedStateHandle[KEY_CATEGORY] = category.name
    }

    /**
     * 更新搜索词：连续输入延迟 [SEARCH_DEBOUNCE_MS] 后生效（250ms 去抖，兼顾跟手与合并），
     * 清空（含纯空白输入）立即生效，避免退出搜索时列表迟滞。
     * 仅「已生效」的搜索词写入恢复句柄：去抖中的中间输入不持久化。
     */
    fun onQueryChanged(rawQuery: String) {
        val query = rawQuery.trim()
        searchDebounceJob?.cancel()
        if (query.isEmpty()) {
            queryFlow.value = ""
            savedStateHandle[KEY_QUERY] = ""
            return
        }
        if (query == queryFlow.value) {
            return
        }
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            queryFlow.value = query
            savedStateHandle[KEY_QUERY] = query
        }
    }

    /**
     * 收藏星乐观更新：立即覆盖星标状态，仓库写入成功并反馈到数据流后释放覆盖，
     * 失败则回滚。成功经事件通道提示 favorite_added / favorite_removed；
     * 失败无专用文案资源，仅静默回滚，不虚构提示。
     *
     * 目标态优先读取同步写入的覆盖表，避免 StateFlow 尚未合成下一帧时重复使用旧收藏态。
     * 写入按点击顺序提交，覆盖仅由对应代次释放；提示文案以仓库实际落点为准。
     */
    fun toggleFavorite(item: ChannelListItem) {
        val favorite = favoriteOverrides.value[item.id]?.favorite ?: (item.id in uiState.value.favorites)
        val requestId = ++favoriteRequestId
        favoriteOverrides.update {
            it + (item.id to FavoriteOverride(favorite = !favorite, requestId = requestId))
        }
        viewModelScope.launch {
            try {
                val landed = favoriteWriteMutex.withLock { repository.toggleFavorite(item.id) }
                // 等待仓库流反映最新收藏后再释放覆盖，避免列表星标短暂回闪；
                // 仓库不按预期发射时（如骨架实现）按超时兜底释放。
                withTimeoutOrNull(FAVORITE_SYNC_GRACE_MS) {
                    repository.observeFavoriteIds().firstOrNull { (item.id in it) == landed }
                }
                clearFavoriteOverride(channelId = item.id, requestId = requestId)
                _events.send(BrowseEvent.FavoriteToggled(added = landed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearFavoriteOverride(channelId = item.id, requestId = requestId)
            }
        }
    }

    /** 仅释放本次请求创建的覆盖，防止旧任务完成或失败时清掉新点击的目标态。 */
    private fun clearFavoriteOverride(channelId: String, requestId: Long) {
        favoriteOverrides.update { overrides ->
            if (overrides[channelId]?.requestId == requestId) overrides - channelId else overrides
        }
    }

    /**
     * 导入频道源：单行 http(s):// 地址走订阅下载，多行输入或其他文本按订阅正文解析。
     *
     * 进行中拒绝重复触发；IllegalArgumentException 归为格式错误、
     * IOException 归为网络不可用，其余异常显示通用失败提示；取消异常原样上抛。
     */
    fun importChannels(rawInput: String) {
        if (!_importInFlight.compareAndSet(expect = false, update = true)) {
            return
        }
        viewModelScope.launch {
            try {
                val input = rawInput.trim()
                if (input.isEmpty()) {
                    _events.send(BrowseEvent.ImportFailed(R.string.import_invalid_url))
                    return@launch
                }
                // 多行裸 URL 是订阅正文；只有单行地址才应发起订阅下载。
                val isUrl = '\n' !in input && '\r' !in input &&
                    (input.startsWith(URL_PREFIX_HTTP, ignoreCase = true) ||
                        input.startsWith(URL_PREFIX_HTTPS, ignoreCase = true))
                val importedCount = if (isUrl) {
                    repository.importFromUrl(input)
                } else {
                    repository.importFromText(input)
                }
                _events.send(BrowseEvent.ImportSucceeded(importedCount))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _events.send(BrowseEvent.ImportFailed(R.string.import_invalid_url))
            } catch (e: IOException) {
                _events.send(BrowseEvent.ImportFailed(R.string.network_unavailable))
            } catch (_: Exception) {
                // 数据库写入等非网络失败也应结束进度并允许重试，不向主线程泄漏异常。
                _events.send(BrowseEvent.ImportFailed(R.string.import_unexpected_error))
            } finally {
                _importInFlight.value = false
            }
        }
    }

    /**
     * 清空全部导入的自定义频道：完成经事件通道提示 remove_custom_done；
     * 失败无专用文案资源，仅静默结束。进行中拒绝重复触发。
     */
    fun clearImportedChannels() {
        if (!_importInFlight.compareAndSet(expect = false, update = true)) {
            return
        }
        viewModelScope.launch {
            try {
                repository.clearImportedChannels()
                _events.send(BrowseEvent.CustomChannelsCleared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 无专用失败文案，保持静默；数据层终态由仓库流自然呈现。
            } finally {
                _importInFlight.value = false
            }
        }
    }

    /**
     * 由数据快照合成当前分类与搜索词下的 UI 状态。
     *
     * 最近观看按 recentIds 顺序映射（最近在前，已删除的频道自动跳过），
     * 其余分类保持仓库原始顺序按频道实体分类过滤；搜索词忽略大小写做包含匹配。
     */
    private fun buildUiState(
        snapshot: ChannelSnapshot,
        query: String,
        category: BrowseCategory,
    ): BrowseUiState {
        val categoryChannels = when (category) {
            BrowseCategory.RECENT -> snapshot.recentIds.mapNotNull(snapshot.channelsById::get)
            else -> snapshot.channels.filter { it.category == category.channelCategory }
        }
        val trimmedQuery = query.trim()
        val filteredChannels = if (trimmedQuery.isEmpty()) {
            categoryChannels
        } else {
            categoryChannels.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
        }
        return BrowseUiState(
            loading = false,
            selectedCategory = category,
            query = trimmedQuery,
            channels = filteredChannels.map { channel ->
                // 收藏状态取自同一快照，保证首帧即为仓库真实值
                ChannelListItem(
                    id = channel.id,
                    name = channel.name,
                    categoryLabelRes = channel.category.labelRes(),
                    sourceCount = channel.sources.size,
                    isFavorite = channel.id in snapshot.favoriteIds,
                )
            },
            favorites = snapshot.favoriteIds,
            // 状态契约（BrowseUiState.emptyReason）：列表非空时为 null，仅在空态给出原因
            emptyReason = if (filteredChannels.isEmpty()) {
                if (trimmedQuery.isEmpty()) EmptyReason.LIST else EmptyReason.SEARCH
            } else {
                null
            },
        )
    }

    /**
     * 合成生效收藏集：仓库值为基础，覆盖表中与仓库不一致的条目按覆盖值生效。
     */
    private fun effectiveFavorites(
        repoFavorites: List<String>,
        overrides: Map<String, FavoriteOverride>,
    ): Set<String> {
        if (overrides.isEmpty()) {
            return repoFavorites.toSet()
        }
        val effective = repoFavorites.toMutableSet()
        overrides.forEach { (channelId, pending) ->
            val favorite = pending.favorite
            // 仓库已反映目标状态的覆盖视为无覆盖（no-op），其余以乐观覆盖为准
            if ((channelId in effective) != favorite) {
                if (favorite) {
                    effective += channelId
                } else {
                    effective -= channelId
                }
            }
        }
        return effective
    }

    /**
     * 尚未由数据库观察流确认的收藏目标。
     *
     * @property favorite 本次点击期望的收藏状态
     * @property requestId 创建覆盖的点击代次
     */
    private data class FavoriteOverride(val favorite: Boolean, val requestId: Long)

    /**
     * 频道目录及可复用索引，与收藏和观看记录的变化解耦。
     *
     * @property channels 仓库顺序的频道全集
     * @property channelsById 频道 id 索引
     */
    private data class ChannelCatalog(
        val channels: List<Channel>,
        val channelsById: Map<String, Channel>,
    )

    /**
     * combine 中间层快照：频道按 id 索引 + 生效收藏集 + 最近观看顺序。
     *
     * @property channels 全部频道（保持仓库原始顺序，供分类过滤）
     * @property channelsById 全部频道按 id 索引（供最近观看顺序映射）
     * @property favoriteIds 生效收藏 id 集合（含乐观覆盖）
     * @property recentIds 最近观看 id 序列（最近在前）
     */
    private data class ChannelSnapshot(
        val channels: List<Channel>,
        val channelsById: Map<String, Channel>,
        val favoriteIds: Set<String>,
        val recentIds: List<String>,
    )

    private companion object {
        /** 恢复句柄键：当前选中分类（存枚举名）。 */
        const val KEY_CATEGORY = "browse_selected_category"

        /** 恢复句柄键：已生效（去抖后）的搜索词。 */
        const val KEY_QUERY = "browse_active_query"

        /** 搜索词去抖间隔（毫秒）。 */
        const val SEARCH_DEBOUNCE_MS = 250L

        /** UI 状态流订阅停止超时（毫秒）：短暂跳转播放页不打断上游。 */
        const val STOP_TIMEOUT_MS = 5_000L

        /** 收藏写入后等待仓库流同步的宽限期（毫秒）。 */
        const val FAVORITE_SYNC_GRACE_MS = 1_000L

        /** 订阅地址前缀：http。 */
        const val URL_PREFIX_HTTP = "http://"

        /** 订阅地址前缀：https。 */
        const val URL_PREFIX_HTTPS = "https://"
    }
}
