package com.sugar.tv.manage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.sugar.tv.R
import com.sugar.tv.databinding.ItemManagerHeaderBinding

/**
 * 将管理操作放在可回收的列表头部，短窗口和大字体下所有入口均能滚动触达。
 *
 * @property onImport 打开系统文件选择器
 * @property onExport 确认后导出列表
 * @property onClearHistory 确认后清理观看记录
 */
class ChannelManagerHeaderAdapter(
    private val onImport: () -> Unit,
    private val onExport: () -> Unit,
    private val onClearHistory: () -> Unit,
) : RecyclerView.Adapter<ChannelManagerHeaderAdapter.Holder>() {

    /** 头部不持有完整列表，避免保留已经删除的频道正文。 */
    private var summary = Summary()

    /** 只刷新实际变化的头部字段，列表 Diff 不会导致按钮重复重绑。 */
    fun render(state: ChannelManagerUiState) {
        val updated = Summary(
            loading = state.loading,
            total = state.totalCount,
            recent = state.recentCount,
            busy = state.busy,
            empty = state.channels.isEmpty(),
            searching = state.query.isNotBlank(),
        )
        if (summary == updated) return
        summary = updated
        notifyItemChanged(0)
    }

    /** 头部始终保留导入与历史清理入口，包括零导入的情况。 */
    override fun getItemCount(): Int = 1

    /** 创建管理卡片并绑定稳定回调。 */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        binding = ItemManagerHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    ).also { holder ->
        holder.binding.managerImport.setOnClickListener { onImport() }
        holder.binding.managerExport.setOnClickListener { onExport() }
        holder.binding.managerClearHistory.setOnClickListener { onClearHistory() }
    }

    /** 按状态回填统计、操作门禁和空态。 */
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val binding = holder.binding
        val context = binding.root.context
        binding.managerSummary.text = context.getString(R.string.manager_summary_format, summary.total, summary.recent)
        binding.managerProgress.isVisible = summary.loading || summary.busy
        binding.managerImport.isEnabled = !summary.busy && !summary.loading
        binding.managerExport.isEnabled = !summary.busy && !summary.loading && summary.total > 0
        binding.managerClearHistory.isEnabled = !summary.busy && !summary.loading && summary.recent > 0
        binding.managerEmpty.isVisible = !summary.loading && summary.empty
        binding.managerEmpty.setText(if (summary.searching) R.string.empty_search_result else R.string.manager_empty)
    }

    /**
     * 头部只托管视图，不保存管理状态。
     *
     * @property binding 卡片视图绑定
     */
    class Holder(val binding: ItemManagerHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 从页面状态提取的轻量头部数据。
     *
     * @property loading 等待首个数据库结果
     * @property total 导入总数
     * @property recent 观看记录总数
     * @property busy 管理操作是否执行中
     * @property empty 当前搜索是否为空列表
     * @property searching 是否正在搜索
     */
    private data class Summary(
        val loading: Boolean = true,
        val total: Int = 0,
        val recent: Int = 0,
        val busy: Boolean = false,
        val empty: Boolean = true,
        val searching: Boolean = false,
    )
}
