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

    /** 只跟踪尚未回收的章节，页面结束时统一清理高度动画。 */
    private val sectionHolders = mutableSetOf<SectionHolder>()

    /** 高度变化会移动后续章节，定位只保留最近一次请求并等待动画完成。 */
    private var pendingLayoutAction: (() -> Unit)? = null

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
            is SectionHolder -> {
                sectionHolders += holder
                holder.bind(row = row, animate = false)
            }
        }
    }

    /** 仅展开集合发生变化时接续当前高度，避免整行重绑造成闪烁或从头播放动画。 */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is SectionHolder && EXPANSION_PAYLOAD in payloads) {
            sectionHolders += holder
            holder.bind(row = getItem(position), animate = true)
        } else {
            onBindViewHolder(holder = holder, position = position)
        }
    }

    /** 临时离屏也要完成动画，缓存 Holder 再出现时不能停在半展开状态。 */
    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is SectionHolder) holder.finishAnimation()
        super.onViewDetachedFromWindow(holder)
    }

    /** 回收前释放动画与正文，之后绑定其他章节时不保留旧目标。 */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is SectionHolder) {
            holder.recycle()
            sectionHolders -= holder
        }
        super.onViewRecycled(holder)
    }

    /** 在 RecyclerView 完成此次绑定的布局之后调用，确保可见行已经启动对应动画。 */
    fun whenLayoutSettled(action: () -> Unit) {
        pendingLayoutAction = action
        runPendingLayoutAction()
    }

    /** 页面销毁时先丢弃定位闭包，再结束所有仍绑定章节的动画。 */
    fun close() {
        pendingLayoutAction = null
        sectionHolders.forEach { it.finishAnimation() }
        sectionHolders.clear()
    }

    /** 最后一个高度动画结束后才定位，避免滚动完成后又被收起卡片推走。 */
    private fun runPendingLayoutAction() {
        if (sectionHolders.any { it.isAnimating }) return
        val action = pendingLayoutAction ?: return
        pendingLayoutAction = null
        action()
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

        /** 上一次绑定的身份用于区分用户切换与回收后的首次显示。 */
        private var boundRow: DocumentRow? = null

        /** 高度、淡入淡出和箭头共用一个动画，避免快速点击时三者不同步。 */
        private val expansion = DocumentSectionAnimator(binding = binding, onSettled = ::runPendingLayoutAction)

        /** 提供给列表的布局等待条件，不向 ViewModel 暴露 View 动画状态。 */
        val isAnimating: Boolean get() = expansion.isRunning

        init {
            binding.sectionHeader.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val row = getItem(position)
                    if (row.collapsible) onToggle(row.sectionIndex)
                }
            }
        }

        /** 目标状态先交给动画器，收起完成后才清正文，让后续卡片连续移动。 */
        fun bind(row: DocumentRow, animate: Boolean) {
            val previous = boundRow
            val sameContent = previous != null && previous.sectionIndex == row.sectionIndex && previous.body === row.body
            val animateChange = animate && sameContent && previous?.expanded != row.expanded
            if (!sameContent) expansion.finish()
            binding.sectionTitle.text = row.title
            binding.sectionHeader.isClickable = row.collapsible
            binding.sectionHeader.isFocusable = row.collapsible
            binding.sectionArrow.isVisible = row.collapsible
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
            if (row.expanded && (!sameContent || binding.sectionBody.text.isNullOrEmpty())) {
                binding.sectionBody.bindDocumentText(body = row.body, renderer = renderer, onOpenLink = onOpenLink)
            }
            boundRow = row
            expansion.setExpanded(expanded = row.expanded, animate = animateChange)
        }

        /** 结束到当前目标后再离屏或回收，正文是否清空由目标展开态决定。 */
        fun finishAnimation() = expansion.finish()

        /** 回收池不保留页面链接 Span 或旧绑定身份，下次显示直接恢复新行的目标状态。 */
        fun recycle() {
            expansion.finish()
            binding.sectionBody.text = null
            binding.sectionBody.movementMethod = null
            boundRow = null
        }
    }

    private companion object {
        /** 概览行类型。 */
        const val OVERVIEW = 0

        /** 章节或版本卡片类型。 */
        const val SECTION = 1

        /** 展开状态专用 payload 不触发默认整行交叉淡入。 */
        const val EXPANSION_PAYLOAD = "document_expansion"

        /** 身份按章节下标比较，展开状态参与内容 Diff。 */
        val ROW_DIFF = object : DiffUtil.ItemCallback<DocumentRow>() {
            /** 同一页面不切换文档，下标可作为稳定章节身份。 */
            override fun areItemsTheSame(oldItem: DocumentRow, newItem: DocumentRow): Boolean =
                oldItem.sectionIndex == newItem.sectionIndex

            /** Span 复用后台结果，不重复比较或排版 Markdown。 */
            override fun areContentsTheSame(oldItem: DocumentRow, newItem: DocumentRow): Boolean = oldItem == newItem

            /** 稳定正文只更新展开状态，首次绑定和内容替换继续走完整绑定。 */
            override fun getChangePayload(oldItem: DocumentRow, newItem: DocumentRow): Any? =
                if (oldItem.copy(expanded = newItem.expanded) == newItem) EXPANSION_PAYLOAD else null
        }
    }
}
