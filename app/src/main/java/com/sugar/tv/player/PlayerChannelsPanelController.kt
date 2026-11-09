package com.sugar.tv.player

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sugar.tv.R
import com.sugar.tv.data.model.Channel
import com.sugar.tv.databinding.ActivityPlayerBinding

/**
 * 选台面板的视图控制器：只负责列表、焦点与分区布局，不持有播放器或业务状态。
 *
 * @param binding 播放页视图；视频和列表必须是同一个 LinearLayout 的兄弟节点
 * @param restoringOpenPanel 页面重建时保留列表滚动位置，不重新定位当前频道
 * @param onClose 收起面板意图
 * @param onQueryChanged 原始搜索文字变化
 * @param onChannelClick 点选频道意图
 */
class PlayerChannelsPanelController(
    private val binding: ActivityPlayerBinding,
    restoringOpenPanel: Boolean,
    private val onClose: () -> Unit,
    private val onQueryChanged: (String) -> Unit,
    onChannelClick: (Channel) -> Unit,
) {
    /** 面板绑定归本页面所有，销毁时解除监听和 Adapter。 */
    private val panel = binding.playerChannelsPanel

    /** 当前视图快照，提交完成时仍须核对开关和查询代次。 */
    private var state: ChannelPanelUiState? = null

    /** 防止旧查询的异步 Diff 回调重新定位列表。 */
    private var rowsGeneration = 0L

    /** 尚未完成的 Diff；快速清空查询时必须提交新代次，不能只与当前已显示列表比较。 */
    private var pendingRows: List<ChannelPanelRow>? = null

    /** 页面销毁后不再访问绑定。 */
    private var disposed = false

    /** 重建沿用 RecyclerView 状态；主动打开才定位当前频道。 */
    private var locatePlayingChannel = !restoringOpenPanel

    /** 新查询先回列表顶部，后续播放高亮变化不打断浏览。 */
    private var scrollToTop = false

    /** 回填恢复文字不重复触发用户输入意图。 */
    private var updatingQuery = false

    /** 稳定系统安全区与 IME 高度由根 Insets 监听统一传入。 */
    private var safeInsets = Insets.NONE

    /** 键盘开启时整个预览和列表收缩到可用区，不让键盘盖住视频。 */
    private var imeBottom = 0

    /** 仅渲染行与转发点击，目录合成仍归 ViewModel。 */
    private val adapter = ChannelPanelAdapter(onChannelClick = onChannelClick)

    /** 窗口变化或 IME 改变可用高度后，按实际空间重新分配视频与列表。 */
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateLayout()
    }

    /** 大字体改变搜索栏高度时，窄窗口也重新为频道行分配可用空间。 */
    private val searchBarLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateLayout()
    }

    /** 用户开始拖动后不再用迟到的当前频道定位打断浏览。 */
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        /** 用户拖动优先于初始定位，不因异步目录返回而跳回播放项。 */
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                locatePlayingChannel = false
                scrollToTop = false
            }
        }
    }

    /** 输入监听只属于面板视图，销毁时主动注销。 */
    private val queryWatcher = panel.channelSearchEdit.doAfterTextChanged { text ->
        if (!updatingQuery) onQueryChanged(text?.toString().orEmpty())
    }

    init {
        panel.channelRecycler.layoutManager = LinearLayoutManager(binding.root.context)
        panel.channelRecycler.adapter = adapter
        panel.channelRecycler.itemAnimator = null
        panel.channelRecycler.addOnScrollListener(scrollListener)
        panel.btnCloseChannels.setOnClickListener { onClose() }
        panel.channelSearchEdit.setOnEditorActionListener { editor, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                ViewCompat.getWindowInsetsController(editor)?.hide(WindowInsetsCompat.Type.ime())
                editor.clearFocus()
                panel.channelRecycler.requestFocus()
                true
            } else {
                false
            }
        }
        ViewCompat.setAccessibilityPaneTitle(
            panel.root,
            binding.root.context.getString(R.string.player_channel_list),
        )
        binding.root.addOnLayoutChangeListener(layoutListener)
        panel.channelSearchBar.addOnLayoutChangeListener(searchBarLayoutListener)
    }

    /** 幂等回填开关与文字；初次恢复不改动 RecyclerView 将恢复的滚动位置。 */
    fun renderState(newState: ChannelPanelUiState) {
        val previous = state
        state = newState
        if (previous != newState) rowsGeneration++
        if (previous != null && previous.query != newState.query) {
            locatePlayingChannel = false
            scrollToTop = true
        }
        if (newState.isOpen && previous?.isOpen == false) {
            locatePlayingChannel = true
            scrollToTop = true
        }
        if (panel.channelSearchEdit.text?.toString() != newState.query) {
            updatingQuery = true
            panel.channelSearchEdit.setText(newState.query)
            panel.channelSearchEdit.setSelection(newState.query.length)
            updatingQuery = false
        }
        if (!newState.isOpen && previous?.isOpen == true) hideKeyboard()
        panel.root.isVisible = newState.isOpen
        updateLayout()
        if (newState.isOpen && previous?.isOpen != true) {
            // 触摸时让根容器接收焦点，键盘 / 遥控器则落到关闭按钮，均不主动打开 IME。
            if (panel.root.isInTouchMode) panel.root.requestFocus() else panel.btnCloseChannels.requestFocus()
        }
    }

    /** 更新绝对避让量；关闭面板后恢复全屏视频，系统安全区由播放覆盖层负责。 */
    fun applyInsets(safe: Insets, keyboardBottom: Int) {
        safeInsets = safe
        imeBottom = keyboardBottom
        updateLayout()
    }

    /** Diff 完成后仅处理当前查询的定位；目录或高亮变化保留用户滚动位置。 */
    fun renderRows(rows: List<ChannelPanelRow>, query: String, catalogEmpty: Boolean) {
        if (disposed || state?.isOpen != true || state?.query != query) return
        val generation = rowsGeneration
        val onCommitted = Runnable {
            if (pendingRows === rows) pendingRows = null
            if (disposed || state?.isOpen != true || generation != rowsGeneration ||
                panel.channelRecycler.adapter !== adapter
            ) return@Runnable
            val manager = panel.channelRecycler.layoutManager as LinearLayoutManager
            val playingPosition = rows.indexOfFirst { it.isPlaying }
            if (locatePlayingChannel && query.isBlank() && playingPosition >= 0) {
                manager.scrollToPositionWithOffset(playingPosition, 0)
                locatePlayingChannel = false
                scrollToTop = false
            } else if (scrollToTop && rows.isNotEmpty()) {
                manager.scrollToPositionWithOffset(0, 0)
                scrollToTop = false
            }
        }
        if (pendingRows == null && adapter.currentList == rows) {
            onCommitted.run()
        } else {
            // 即使已显示内容相等，也要让新提交作废仍在后台计算的旧查询结果。
            pendingRows = rows
            adapter.submitList(rows, onCommitted)
        }
        panel.channelEmptyContainer.isVisible = rows.isEmpty()
        panel.channelRecycler.isVisible = rows.isNotEmpty()
        if (rows.isEmpty()) {
            panel.channelEmptyText.setText(
                if (catalogEmpty) R.string.empty_channel_list else R.string.empty_search_result,
            )
        }
    }

    /** 前后台或面板关闭时收起键盘，保留查询和滚动状态。 */
    fun hideKeyboard() {
        ViewCompat.getWindowInsetsController(panel.root)?.hide(WindowInsetsCompat.Type.ime())
        if (panel.channelSearchEdit.hasFocus()) panel.root.requestFocus()
        panel.channelSearchEdit.clearFocus()
    }

    /** 宽窗口末端并排选台；窄窗口视频置顶，优先给搜索栏和至少一行频道留空间。 */
    private fun updateLayout() {
        if (disposed) return
        val open = state?.isOpen == true
        val root = binding.root
        val left = if (open) safeInsets.left else 0
        val top = if (open) safeInsets.top else 0
        val right = if (open) safeInsets.right else 0
        val bottom = if (open) maxOf(safeInsets.bottom, imeBottom) else 0
        if (root.paddingLeft != left || root.paddingTop != top ||
            root.paddingRight != right || root.paddingBottom != bottom
        ) {
            root.updatePadding(left = left, top = top, right = right, bottom = bottom)
        }
        val width = (root.width - left - right).coerceAtLeast(0)
        val height = (root.height - top - bottom).coerceAtLeast(0)
        val resources = root.resources
        val panelMinWidth = resources.getDimensionPixelSize(R.dimen.dp_280)
        val previewMinWidth = resources.getDimensionPixelSize(R.dimen.dp_320)
        if (!open || width >= panelMinWidth + previewMinWidth) {
            root.orientation = LinearLayout.HORIZONTAL
            setSize(view = binding.playerStage, width = 0, height = ViewGroup.LayoutParams.MATCH_PARENT, weight = 1f)
            setSize(
                view = panel.root,
                width = (width / 3).coerceIn(panelMinWidth, previewMinWidth),
                height = ViewGroup.LayoutParams.MATCH_PARENT,
                weight = 0f,
            )
        } else {
            root.orientation = LinearLayout.VERTICAL
            val searchHeight = panel.channelSearchBar.height + panel.root.paddingTop
            val rowHeight = resources.getDimensionPixelSize(R.dimen.dp_48)
            setSize(
                view = binding.playerStage,
                width = ViewGroup.LayoutParams.MATCH_PARENT,
                // 目标预览为 16:9；极短窗口按剩余空间限高，视频仍由 PlayerView 等比适配。
                height = minOf(width * 9 / 16, height / 3, (height - searchHeight - rowHeight).coerceAtLeast(0)),
                weight = 0f,
            )
            setSize(view = panel.root, width = ViewGroup.LayoutParams.MATCH_PARENT, height = 0, weight = 1f)
        }
    }

    /** 仅尺寸真正变化时请求布局，避免尺寸监听重复触发布局。 */
    private fun setSize(view: View, width: Int, height: Int, weight: Float) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        if (params.width == width && params.height == height && params.weight == weight) return
        params.width = width
        params.height = height
        params.weight = weight
        view.layoutParams = params
    }

    /** 解除属于视图的监听；已提交 Diff 的回调通过 disposed 标记拒绝旧 Binding。 */
    fun dispose() {
        disposed = true
        binding.root.removeOnLayoutChangeListener(layoutListener)
        panel.channelSearchBar.removeOnLayoutChangeListener(searchBarLayoutListener)
        panel.channelSearchEdit.removeTextChangedListener(queryWatcher)
        panel.channelSearchEdit.setOnEditorActionListener(null)
        panel.btnCloseChannels.setOnClickListener(null)
        panel.channelRecycler.removeOnScrollListener(scrollListener)
        panel.channelRecycler.adapter = null
        pendingRows = null
    }
}
