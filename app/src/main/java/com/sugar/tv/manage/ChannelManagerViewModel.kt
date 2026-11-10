package com.sugar.tv.manage

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sugar.tv.R
import com.sugar.tv.TvApplication
import com.sugar.tv.data.model.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 导入频道管理的不可变快照，不将频道正文存入恢复句柄。
 *
 * @property loading 是否等待首次数据库结果
 * @property channels 搜索后的导入频道
 * @property totalCount 未过滤的导入频道数
 * @property recentCount 全部观看记录数，包含已删除频道的弱关联记录
 * @property query 当前查询原文
 * @property busy 是否正在执行文件或数据库写入
 */
data class ChannelManagerUiState(
    val loading: Boolean = true,
    val channels: List<Channel> = emptyList(),
    val totalCount: Int = 0,
    val recentCount: Int = 0,
    val query: String = "",
    val busy: Boolean = false,
)

/**
 * 管理操作完成后的提示；文件 URI 和正文不进入事件，避免长期持有授权或大对象。
 *
 * @property messageRes 本地化提示资源
 * @property count 可选的频道数位置参数
 */
data class ChannelManagerNotice(@StringRes val messageRes: Int, val count: Int? = null)

/**
 * 协调本地文件导入、导出、单频道删除和观看记录清理。
 *
 * @param application 仅用于取得应用级仓库
 * @param savedStateHandle 只保存管理页的搜索词
 */
class ChannelManagerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    /** 文件访问和数据库操作统一由仓库调度到后台。 */
    private val repository = (application as TvApplication).channelRepository

    /** 查询保留原文，筛选时才去除两侧空白。 */
    private val query = MutableStateFlow(savedStateHandle.get<String>(KEY_QUERY).orEmpty())

    /** 所有管理写入共用互斥门禁，防止导出和删除相互穿插。 */
    private val busy = MutableStateFlow(false)

    /** 任务结果跨页面短暂不可见期间缓冲，恢复后展示。 */
    private val notices = EventChannel<ChannelManagerNotice>(capacity = EventChannel.BUFFERED)

    /** 页面只在 STARTED 期间收集提示。 */
    val notice = notices.receiveAsFlow()

    /** 过滤与计数在后台合成，离开页面五秒后停止观察数据库。 */
    val uiState: StateFlow<ChannelManagerUiState> = combine(
        repository.observeImportedChannels(),
        repository.observeRecentIds(),
        query,
        busy,
    ) { channels, recentIds, search, inFlight ->
        val trimmed = search.trim()
        ChannelManagerUiState(
            loading = false,
            channels = if (trimmed.isEmpty()) channels else channels.filter {
                it.name.contains(trimmed, ignoreCase = true)
            },
            totalCount = channels.size,
            recentCount = recentIds.size,
            query = search,
            busy = inFlight,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ChannelManagerUiState(query = query.value),
    )

    /** 搜索状态归 ViewModel，重建后恢复且不受列表条目回收影响。 */
    fun search(value: String) {
        query.value = value
        savedStateHandle[KEY_QUERY] = value
    }

    /** 系统选择器授权仅用于本次导入，不保存文件位置或订阅任务。 */
    fun importDocument(uri: Uri) = runOperation(invalidMessageRes = R.string.import_invalid_url) {
        ChannelManagerNotice(
            messageRes = R.string.import_success_format,
            count = repository.importFromDocument(uri = uri),
        )
    }

    /** 导出全部导入频道，搜索词只影响显示，不截断导出范围。 */
    fun exportDocument(uri: Uri) = runOperation {
        ChannelManagerNotice(
            messageRes = R.string.manager_export_success_format,
            count = repository.exportToDocument(uri = uri),
        )
    }

    /** 只删除指定导入记录，收藏和观看记录继续采用弱关联。 */
    fun removeChannel(channelId: String) = runOperation {
        val removed = repository.removeImportedChannel(channelId = channelId)
        ChannelManagerNotice(
            messageRes = if (removed) R.string.manager_channel_removed else R.string.manager_operation_failed,
        )
    }

    /** 清空观看记录，不触碰收藏或导入列表。 */
    fun clearHistory() = runOperation {
        repository.clearRecentChannels()
        ChannelManagerNotice(messageRes = R.string.manager_history_cleared)
    }

    /** 公共写入入口统一处理进度和失败；取消异常原样交回协程。 */
    private fun runOperation(
        @StringRes invalidMessageRes: Int = R.string.manager_operation_failed,
        operation: suspend () -> ChannelManagerNotice,
    ) {
        if (!busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                notices.send(operation())
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalArgumentException) {
                notices.send(ChannelManagerNotice(messageRes = invalidMessageRes))
            } catch (_: Exception) {
                notices.send(ChannelManagerNotice(messageRes = R.string.manager_operation_failed))
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        /** 搜索恢复键，不保存 URI 或列表正文。 */
        const val KEY_QUERY = "manager_query"
    }
}
