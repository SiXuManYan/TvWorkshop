package com.sugar.tv.about

import android.content.Context
import android.text.Spanned
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sugar.tv.R
import com.sugar.tv.databinding.ItemDocumentOverviewBinding
import com.sugar.tv.databinding.ItemDocumentSectionBinding
import io.noties.markwon.Markwon

/**
 * 回收列表行模型，概览使用 -1，其余下标在当前文档内保持稳定。
 *
 * @property sectionIndex 章节身份，与章节导航共用下标。
 * @property title 概览或章节标题。
 * @property body 后台准备的不可变正文。
 * @property collapsible 是否提供版本折叠操作。
 * @property expanded 正文是否可见。
 */
internal data class DocumentRow(
    val sectionIndex: Int,
    val title: String,
    val body: Spanned,
    val collapsible: Boolean = false,
    val expanded: Boolean = true,
)

/**
 * 概览、法律章节和日志卡片共用回收列表，只绑定可见区域附近的 TextView。
 *
 * @param context 当前页面主题，仅用于主线程的 TextView 绑定钩子。
 * @property onToggle 将版本展开意图交回 ViewModel。
 * @property onOpenLink 将链接点击交回当前页面，数据层不持有页面引用。
 */
internal class LocalDocumentAdapter(
    context: Context,
    private val onToggle: (Int) -> Unit,
    private val onOpenLink: (String) -> Unit,
) : ListAdapter<DocumentRow, RecyclerView.ViewHolder>(ROW_DIFF) {

    /** 包含尚在 Diff 队列中的目标，连续展开/收起不能仅比较已经显示的旧列表。 */
    private var submittedRows: List<DocumentRow>? = null

    /** 只调用 setParsedMarkdown，不在主线程解析正文，也不与仓库共用跨线程渲染器。 */
    private val renderer = Markwon.create(context)

    init {
        setHasStableIds(true)
        // 异步加载时不让空列表消费恢复位置，等正文提交后再恢复 LayoutManager 状态。
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    /** 只将变化的展开状态提交给 DiffUtil；提交完成后再处理章节定位。 */
    fun render(state: LocalDocumentUiState, collapsible: Boolean, onCommitted: () -> Unit) {
        val rows = state.content?.let { content ->
            listOf(DocumentRow(sectionIndex = -1, title = content.title, body = content.introduction)) +
                content.sections.mapIndexed { index, section ->
                    DocumentRow(
                        sectionIndex = index,
                        title = section.title,
                        body = section.body,
                        collapsible = collapsible,
                        expanded = index in state.expandedSections,
                    )
                }
        }.orEmpty()
        if (submittedRows == rows) {
            if (currentList == rows) onCommitted()
            return
        }
        // 即使新目标恰好等于 currentList，也要提交以作废正在计算的相反状态。
        submittedRows = rows
        submitList(rows, onCommitted)
    }

    /** 0 留给概览，章节从 1 开始，避开 RecyclerView.NO_ID。 */
    override fun getItemId(position: Int): Long = getItem(position).sectionIndex.toLong() + 1

    /** 概览与章节各自使用专门的 ViewBinding 布局。 */
    override fun getItemViewType(position: Int): Int = if (getItem(position).sectionIndex < 0) OVERVIEW else SECTION

    /** 按需创建可见行，不在进入页面时构造整篇长文的所有 View。 */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == OVERVIEW) {
            OverviewHolder(binding = ItemDocumentOverviewBinding.inflate(inflater, parent, false))
        } else {
            SectionHolder(binding = ItemDocumentSectionBinding.inflate(inflater, parent, false))
        }
    }

    /** 按模型绝对值回填，避免回收后遗留旧标题、箭头或展开状态。 */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is OverviewHolder -> holder.bind(row = row)
            is SectionHolder -> holder.bind(row = row)
        }
    }

    /**
     * 文档标题、语言提示和更新日期概览。
     *
     * @property binding 仅由列表 ViewHolder 持有的行视图。
     */
    private inner class OverviewHolder(private val binding: ItemDocumentOverviewBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** 概览正文仍支持已登记的本地文档互链。 */
        fun bind(row: DocumentRow) {
            binding.documentHeading.text = row.title
            binding.documentIntro.bindDocumentText(body = row.body, renderer = renderer, onOpenLink = onOpenLink)
            binding.documentIntro.isVisible = row.body.isNotEmpty()
        }
    }

    /**
     * 法律章节始终展开，日志标题行可用遥控器、键盘与 TalkBack 激活。
     *
     * @property binding 仅由列表 ViewHolder 持有的行视图。
     */
    private inner class SectionHolder(private val binding: ItemDocumentSectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.sectionHeader.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val row = getItem(position)
                    if (row.collapsible) onToggle(row.sectionIndex)
                }
            }
        }

        /** 收起时清空 View 正文，既退出无障碍遍历，也释放页面链接回调。 */
        fun bind(row: DocumentRow) {
            binding.sectionTitle.text = row.title
            binding.sectionHeader.isClickable = row.collapsible
            binding.sectionHeader.isFocusable = row.collapsible
            binding.sectionArrow.isVisible = row.collapsible
            binding.sectionArrow.rotation = if (row.expanded) 180f else 0f
            ViewCompat.setStateDescription(
                binding.sectionHeader,
                if (row.collapsible) {
                    binding.root.context.getString(
                        if (row.expanded) R.string.document_expanded else R.string.document_collapsed,
                    )
                } else {
                    null
                },
            )
            binding.sectionBody.isVisible = row.expanded
            if (row.expanded) {
                binding.sectionBody.bindDocumentText(body = row.body, renderer = renderer, onOpenLink = onOpenLink)
            } else {
                binding.sectionBody.text = null
            }
        }
    }

    private companion object {
        /** 概览行类型。 */
        const val OVERVIEW = 0

        /** 章节或版本卡片类型。 */
        const val SECTION = 1

        /** 身份按章节下标比较，展开状态参与内容 Diff。 */
        val ROW_DIFF = object : DiffUtil.ItemCallback<DocumentRow>() {
            /** 同一页面不切换文档，下标可作为稳定章节身份。 */
            override fun areItemsTheSame(oldItem: DocumentRow, newItem: DocumentRow): Boolean =
                oldItem.sectionIndex == newItem.sectionIndex

            /** Span 复用后台结果，不重复比较或排版 Markdown。 */
            override fun areContentsTheSame(oldItem: DocumentRow, newItem: DocumentRow): Boolean = oldItem == newItem
        }
    }
}
