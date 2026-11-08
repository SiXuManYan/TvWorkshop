package com.sugar.tv.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sugar.tv.R
import com.sugar.tv.base.BaseActivity
import com.sugar.tv.databinding.ActivityLocalDocumentBinding
import kotlinx.coroutines.launch

/** 三份中文文档共用的原生阅读页：固定章节导航、回顶、日志折叠与浏览器外链。 */
class LocalDocumentActivity : BaseActivity() {

    /** 页面持有视图；构造期不读取 Context 或资源。 */
    private lateinit var binding: ActivityLocalDocumentBinding

    /** 列表只渲染不可变章节，展开意图由 ViewModel 接管。 */
    private lateinit var documentAdapter: LocalDocumentAdapter

    /** 默认工厂把 Intent extras 和进程恢复值合并到 SavedStateHandle。 */
    private val viewModel: LocalDocumentViewModel by lazy {
        ViewModelProvider(this)[LocalDocumentViewModel::class.java]
    }

    /** 内容身份变化才重建导航，不随卡片展开或生命周期重放重复创建。 */
    private var renderedContent: DocumentContent? = null

    /** 页面持有的章节 Chip，顺序与列表下标一致。 */
    private val chapterChips = mutableListOf<Chip>()

    /** 当前高亮章节，仅变化时同步 Chip 并将其滚动到横向可见区域。 */
    private var activeChapter = -1

    /** 等待 Diff 提交完成的目标下标，避免定位到尚未展开的旧行。 */
    private var pendingSection: Int? = null

    /** 外链确认窗口只属于当前页面，离开时主动解除引用。 */
    private var externalDialog: AlertDialog? = null

    /** 安装原生工具栏、章节列表和加载/失败出口。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyDocumentInsets()
        title = getString(viewModel.document.titleRes)
        binding.documentToolbar.title = title
        binding.documentToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.documentToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.document_back_to_top) {
                pendingSection = null
                (binding.documentList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
                true
            } else {
                false
            }
        }
        pendingSection = savedInstanceState?.getInt(KEY_PENDING_SECTION, -1)?.takeIf { it >= 0 }
        binding.documentRetry.setOnClickListener { viewModel.retry() }
        setupList()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    /** 禁用整行变更动画，展开由章节自己的高度、透明度与箭头动画接管。 */
    private fun setupList() {
        documentAdapter = LocalDocumentAdapter(
            context = this,
            onToggle = viewModel::toggleSection,
            onOpenLink = ::openDocumentLink,
        )
        binding.documentList.apply {
            layoutManager = LinearLayoutManager(this@LocalDocumentActivity)
            adapter = documentAdapter
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                /** 从首个可见章节同步高亮，概览可见时归属第一章。 */
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateActiveChapter()
            })
        }
    }

    /** 互斥展示加载、错误和正文，异步提交完成后再恢复导航与待处理定位。 */
    private fun render(state: LocalDocumentUiState) {
        binding.documentLoading.isVisible = state.loading
        binding.documentError.isVisible = state.failed
        binding.documentRetry.isVisible = state.failed
        binding.documentStatus.isVisible = state.loading || state.failed
        binding.documentList.isVisible = state.content != null
        binding.documentChaptersScroll.isVisible = state.content != null
        binding.documentToolbar.menu.findItem(R.id.document_back_to_top).isEnabled = state.content != null
        state.content?.let { content ->
            if (renderedContent !== content) buildChapters(content = content)
        }
        documentAdapter.render(
            state = state,
            collapsible = viewModel.document.collapsible,
            onCommitted = committed@ {
                if (isDestroyed) return@committed
                binding.documentList.doOnLayout {
                    if (isDestroyed) return@doOnLayout
                    // payload 在列表布局时才绑定；先等本轮绑定，再等待所有章节高度稳定。
                    documentAdapter.whenLayoutSettled {
                        // 离屏 Holder 也可能在 RecyclerView 布局中结束动画，定位延后一轮再执行。
                        binding.documentList.post {
                            if (!isDestroyed) {
                                binding.documentList.doOnLayout {
                                    if (!isDestroyed && !applyPendingSection()) updateActiveChapter()
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    /** 复用工坊系列的横向章节胶囊，触达范围由 Chip 扩至至少 48dp。 */
    private fun buildChapters(content: DocumentContent) {
        renderedContent = content
        activeChapter = -1
        chapterChips.clear()
        binding.documentChapters.removeAllViews()
        content.sections.forEachIndexed { index, section ->
            val chip = layoutInflater.inflate(
                R.layout.item_document_chapter,
                binding.documentChapters,
                false,
            ) as Chip
            chip.id = View.generateViewId()
            chip.text = section.title
            chip.setOnClickListener { revealSection(index = index) }
            chapterChips += chip
            binding.documentChapters.addView(chip)
        }
    }

    /** 点击章节时先展开版本，再等待最新列表提交；重复点击也可重新定位。 */
    private fun revealSection(index: Int) {
        pendingSection = index
        viewModel.expandSection(index = index)
        render(state = viewModel.uiState.value)
    }

    /** 章节位置统一右移一行以跳过概览；Diff 未就绪时保留目标等待下一次提交。 */
    private fun applyPendingSection(): Boolean {
        val index = pendingSection ?: return false
        val row = documentAdapter.currentList.getOrNull(index + 1) ?: return false
        if (!row.expanded) return false
        pendingSection = null
        val manager = binding.documentList.layoutManager as LinearLayoutManager
        // 列表已有顶部留白；额外正偏移会让上一章仍被判定为首个可见项，导致高亮回跳。
        manager.scrollToPositionWithOffset(index + 1, 0)
        selectChapter(index = index)
        return true
    }

    /** RecyclerView 恢复滚动位置后同样从可见章节推导高亮，不另外保存重复状态。 */
    private fun updateActiveChapter() {
        val manager = binding.documentList.layoutManager as LinearLayoutManager
        val position = manager.findFirstVisibleItemPosition()
        if (position != RecyclerView.NO_POSITION) selectChapter(index = (position - 1).coerceAtLeast(0))
    }

    /** 仅同步变化的选中项，长目录自动揭示当前章节，不改变正文滚动位置。 */
    private fun selectChapter(index: Int) {
        if (index == activeChapter || index !in chapterChips.indices) return
        activeChapter = index
        val chip = chapterChips[index]
        binding.documentChapters.check(chip.id)
        chip.requestRectangleOnScreen(Rect(0, 0, chip.width, chip.height), true)
    }

    /** 本地协议互链走白名单，HTTP(S) 链接经确认后交系统浏览器处理。 */
    private fun openDocumentLink(link: String) {
        LocalDocument.fromLink(link = link)?.let { document ->
            if (document != viewModel.document) startActivity(createIntent(context = this, document = document))
            return
        }
        val uri = Uri.parse(link)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return
        externalDialog?.dismiss()
        externalDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.document_external_title)
            .setMessage(link)
            .setNegativeButton(R.string.document_external_cancel, null)
            .setPositiveButton(R.string.document_external_open) { _, _ -> openBrowser(uri = uri) }
            .create().also { dialog ->
                dialog.setOnDismissListener { externalDialog = null }
                dialog.show()
            }
    }

    /** 系统没有浏览器时展示可恢复提示，不让文档页因外部应用缺失而崩溃。 */
    private fun openBrowser(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.document_external_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** 小型待定位目标随页面保存；正文和版本展开集合由 ViewModel 管理。 */
    override fun onSaveInstanceState(outState: Bundle) {
        pendingSection?.let { outState.putInt(KEY_PENDING_SECTION, it) }
        super.onSaveInstanceState(outState)
    }

    /** 页面销毁时解除列表与对话框回调，后台内容不持有当前 Activity。 */
    override fun onDestroy() {
        externalDialog?.dismiss()
        externalDialog = null
        documentAdapter.close()
        binding.documentList.clearOnScrollListeners()
        binding.documentList.adapter = null
        chapterChips.clear()
        super.onDestroy()
    }

    companion object {
        /** 等待列表提交的 UI 定位目标，使用实例状态而非大型正文数据。 */
        private const val KEY_PENDING_SECTION = "document_pending_section"

        /** 应用内导航只传枚举标识，不允许外部地址替换 assets 路径。 */
        internal fun createIntent(context: Context, document: LocalDocument): Intent =
            Intent(context, LocalDocumentActivity::class.java).putExtra(LocalDocument.EXTRA_DOCUMENT, document.key)
    }
}
