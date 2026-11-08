package com.sugar.tv.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 负责文档读取、重试和版本展开状态，页面重建不重复排版。
 *
 * @param application 只为本地文档仓库提供 Application 级依赖。
 * @property savedStateHandle 保存文档标识和展开下标，不保存整篇正文。
 */
internal class LocalDocumentViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    /** 经白名单解析的当前文档；Intent extras 会进入默认恢复句柄。 */
    val document = LocalDocument.fromKey(savedStateHandle[LocalDocument.EXTRA_DOCUMENT])

    /** 页面作用域仓库：只使用 Application，不持有页面与对话框。 */
    private val repository = LocalDocumentRepository(application = application)

    /** 可变状态仅在 ViewModel 内更新。 */
    private val mutableState = MutableStateFlow(LocalDocumentUiState())

    /** 页面在 STARTED 期间观察的只读状态。 */
    val uiState = mutableState.asStateFlow()

    /** 重试前取消旧读取，所有依赖字段均在 init 启动任务之前初始化。 */
    private var loadJob: Job? = null

    init {
        load()
    }

    /** 失败态显式重试；正常阅读期间不重复读取相同资产。 */
    fun retry() {
        if (mutableState.value.failed) load()
    }

    /** 日志卡片各自展开或收起，法律文档不提供隐藏条款的折叠操作。 */
    fun toggleSection(index: Int) {
        if (!canExpand(index = index)) return
        mutableState.update { state ->
            val expanded = if (index in state.expandedSections) {
                state.expandedSections - index
            } else {
                state.expandedSections + index
            }
            state.copy(expandedSections = expanded)
        }
        saveExpandedSections()
    }

    /** 导航到日志版本前先展开目标，避免只定位到收起的标题。 */
    fun expandSection(index: Int) {
        if (!canExpand(index = index) || index in mutableState.value.expandedSections) return
        mutableState.update { it.copy(expandedSections = it.expandedSections + index) }
        saveExpandedSections()
    }

    /** 只允许有效的日志章节改变展开集合。 */
    private fun canExpand(index: Int): Boolean =
        document.collapsible && index in (mutableState.value.content?.sections?.indices ?: IntRange.EMPTY)

    /** 展开集合按小型 IntArray 保存，支持进程重建；空数组表示用户主动收起全部。 */
    private fun saveExpandedSections() {
        savedStateHandle[KEY_EXPANDED] = mutableState.value.expandedSections.toIntArray()
    }

    /** 读取并恢复有效下标，首开日志默认展开最新条目，协议与隐私始终展开全部。 */
    private fun load() {
        loadJob?.cancel()
        mutableState.update { LocalDocumentUiState() }
        loadJob = viewModelScope.launch {
            try {
                val content = repository.load(document = document)
                ensureActive()
                val expanded = if (document.collapsible) {
                    savedStateHandle.get<IntArray>(KEY_EXPANDED)?.toSet()
                        ?.intersect(content.sections.indices.toSet()) ?: setOf(0)
                } else {
                    content.sections.indices.toSet()
                }
                mutableState.update {
                    LocalDocumentUiState(content = content, expandedSections = expanded, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                mutableState.update { LocalDocumentUiState(loading = false, failed = true) }
            }
        }
    }

    private companion object {
        /** 只在当前文档页面的恢复句柄中使用。 */
        const val KEY_EXPANDED = "document_expanded_sections"
    }
}
