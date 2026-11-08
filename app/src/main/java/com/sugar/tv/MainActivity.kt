package com.sugar.tv

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sugar.tv.base.BaseActivity
import com.sugar.tv.browse.BrowseCategory
import com.sugar.tv.browse.BrowseEvent
import com.sugar.tv.browse.BrowseUiState
import com.sugar.tv.browse.BrowseViewModel
import com.sugar.tv.browse.ChannelListAdapter
import com.sugar.tv.browse.ChannelListItem
import com.sugar.tv.databinding.ActivityMainBinding
import com.sugar.tv.databinding.DialogImportBinding
import com.sugar.tv.player.PlayerActivity
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 主页：频道分类、搜索、收藏与频道源导入的浏览页。
 *
 * 只承担生命周期、窗口与 Insets、导航、对话框和 ViewBinding 渲染；
 * 业务状态全部由 [BrowseViewModel] 持有，数据访问由频道数据仓库完成。
 *
 * Edge-to-edge：列表内容延伸到系统栏与顶部浮层后方，浮层为半透明表面（背景 230/255），
 * 避让量按 Insets 与浮层实测高度计算，均按绝对值设置、不重复累加。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var channelAdapter: ChannelListAdapter

    /** 导入对话框与其内容 Binding：导入进行中禁用按钮并显示进度。 */
    private var importDialog: AlertDialog? = null
    private var importDialogBinding: DialogImportBinding? = null

    /** 浏览页 ViewModel：延迟创建，配置变化间保留状态。 */
    private val viewModel: BrowseViewModel by lazy {
        ViewModelProvider(this).get(BrowseViewModel::class.java)
    }

    /** 最近一次渲染的分类：变化时触发列表回顶。 */
    private var lastRenderedCategory: BrowseCategory? = null

    /** 当前展示的 Snackbar：新提示出现前关闭旧提示，避免快速连击时提示堆叠。 */
    private var currentSnackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 工具栏接管系统 ActionBar，菜单（展开式搜索 / 导入）经由 onCreateOptionsMenu 装配
        setSupportActionBar(binding.browseToolbar)
        applyOverlaySurfaceAlpha()
        setupChannelList()
        setupCategoryChips()
        setupWindowInsets()
        observeViewModel()
    }

    override fun onDestroy() {
        // 对话框不由 ViewModel 持有，销毁时主动解除，避免窗口泄漏
        importDialog?.dismiss()
        importDialog = null
        importDialogBinding = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        setupSearchView(menu.findItem(R.id.menu_search))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_import -> {
                showImportDialog()
                true
            }
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 「关于」对话框：向用户说明内置频道来源、版权归属、源失效的兜底方式与隐私边界。
     * 简述参考项目与数据请求边界，详细来源记录及许可说明见项目 README。
     */
    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    /**
     * 沉浸式浮层表面：沿用 230/255 常规沉浸表面约定，仅降低浮层背景不透明度，
     * 不降低标题、菜单与 Chip 等控件本身的 alpha。
     */
    private fun applyOverlaySurfaceAlpha() {
        binding.browseOverlayContainer.background?.alpha = OVERLAY_SURFACE_ALPHA
    }

    /**
     * 频道列表：LayoutManager + ListAdapter，条目点击与收藏星操作委托回调处理。
     *
     * 性能调优：条目高度统一（单行频道名）启用 hasFixedSize；加大离屏缓存减少回滚重绑定；
     * 变更动画关闭（遵循作品页策略：数据刷新不做条目内闪烁动画，仅保留增删与移动动画）。
     */
    private fun setupChannelList() {
        channelAdapter = ChannelListAdapter(
            onChannelClick = ::openPlayer,
            onFavoriteClick = viewModel::toggleFavorite,
        )
        binding.browseChannelList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
            clipToPadding = false
            setHasFixedSize(true)
            setItemViewCacheSize(RECYCLER_CACHE_SIZE)
            (itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
        }
    }

    /**
     * 分类 Chip：程序化生成（顺序即契约顺序），单选且必选；
     * 选中态用主题主色填充，未选中保持描边胶囊样式。
     */
    private fun setupCategoryChips() {
        BrowseCategory.entries.forEach { category ->
            binding.browseCategoryChips.addView(buildCategoryChip(category))
        }
    }

    /**
     * 构建单个分类 Chip：文案取分类契约资源，选中态前景背景均为主题色。
     */
    private fun buildCategoryChip(category: BrowseCategory): Chip {
        val chip = Chip(this)
        chip.tag = category
        chip.text = getString(category.labelRes)
        // 程序化生成默认无勾选图标，选中态完全由配色表达
        chip.isCheckable = true
        chip.checkedIcon = null
        // 触达目标扩到 48dp：Material Chip 默认视觉高度 32dp，不满足可操作目标约定
        chip.setEnsureMinTouchTargetSize(true)
        chip.chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                MaterialColors.getColor(chip, androidx.appcompat.R.attr.colorPrimary),
                Color.TRANSPARENT,
            ),
        )
        chip.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimary),
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurfaceVariant),
                ),
            )
        )
        // 仅响应「选中」方向；程序化同步触发的取消选中不回写，状态流去重也不会形成循环
        chip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.onCategorySelected(category)
            }
        }
        return chip
    }

    /**
     * 按状态同步 Chip 选中态：赋值为布尔绝对值，幂等且不累积。
     */
    private fun syncSelectedChip(selected: BrowseCategory) {
        binding.browseCategoryChips.children.filterIsInstance<Chip>().forEach { chip ->
            chip.isChecked = (chip.tag as? BrowseCategory) == selected
        }
    }

    /**
     * 窗口 Insets 避让：
     * - 顶部浮层 padding = 状态栏（含挖孔）；
     * - 列表顶部 padding = 浮层实测高度（首帧布局后同步，内容从浮层下方开始滚动）；
     * - 列表底部 padding = max(导航栏, 输入法) + 内容留白，保证末条可滚动到安全区。
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.browseOverlayContainer) { overlay, insets ->
            overlay.updatePadding(top = insets.getInsets(BAR_INSETS_MASK).top)
            // 状态栏避让改变浮层高度后，重新同步列表顶部留白（按绝对值设置，幂等）
            overlay.post { syncListTopPadding() }
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.browseChannelList) { list, insets ->
            list.updatePadding(
                bottom = insets.getInsets(BOTTOM_INSETS_MASK).bottom +
                    resources.getDimensionPixelSize(R.dimen.dp_16)
            )
            insets
        }
        binding.browseOverlayContainer.doOnLayout { syncListTopPadding() }
    }

    /**
     * 列表顶部留白 = 顶部浮层实测高度；高度未就绪时跳过，等待下一次同步。
     */
    private fun syncListTopPadding() {
        val overlayHeight = binding.browseOverlayContainer.height
        if (overlayHeight > 0) {
            binding.browseChannelList.updatePadding(top = overlayHeight)
        }
    }

    /**
     * 收集 ViewModel：状态渲染、导入进度与一次性事件均在 STARTED 期间进行，
     * 离开页面自动停采，返回后继续（缓冲事件按序补发）。
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.importInFlight.collect(::syncImportProgress) }
                launch { viewModel.events.receiveAsFlow().collect(::onBrowseEvent) }
            }
        }
    }

    /**
     * 渲染 UI 状态：加载态 / 空态 / 列表三态互斥展示，Chip 选中态同步；
     * 分类切换时列表回到顶部，避免停留在上一分类的滚动位置造成位置错觉。
     */
    private fun render(state: BrowseUiState) {
        binding.browseLoadingView.isVisible = state.loading
        binding.browseEmptyView.isVisible = !state.loading && state.channels.isEmpty()
        binding.browseChannelList.isVisible = !state.loading && state.channels.isNotEmpty()
        if (!state.loading && state.channels.isEmpty()) {
            state.emptyReason?.let { binding.browseEmptyView.setText(it.messageRes) }
        }
        // 内容未变化不重复提交，避免无谓的 Diff 运算
        if (channelAdapter.currentList != state.channels) {
            channelAdapter.submitList(state.channels)
        }
        if (state.selectedCategory != lastRenderedCategory) {
            lastRenderedCategory = state.selectedCategory
            binding.browseChannelList.post { binding.browseChannelList.scrollToPosition(0) }
        }
        syncSelectedChip(state.selectedCategory)
    }

    /**
     * 工具栏搜索：展开式 SearchView，输入变化交由 ViewModel 做 250ms 去抖；
     * 收起搜索即清空查询，保持「无搜索框 = 全量列表」的一致状态；
     * 页面重建后恢复仍在生效的搜索词。
     */
    private fun setupSearchView(searchItem: MenuItem) {
        val searchView = searchItem.actionView as? SearchView ?: return
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.onQueryChanged(query.orEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.onQueryChanged(newText.orEmpty())
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.onQueryChanged("")
                return true
            }
        })
        val activeQuery = viewModel.uiState.value.query
        if (activeQuery.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(activeQuery, false)
            searchView.clearFocus()
        }
    }

    /**
     * 跳转播放页：仅传递频道 id，播放页按 [PlayerActivity.EXTRA_CHANNEL_ID] 约定读取。
     */
    private fun openPlayer(item: ChannelListItem) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, item.id)
        startActivity(intent)
    }

    /**
     * 导入频道源对话框：多行输入，输入为空时禁用导入；
     * 导入进行中禁用按钮并显示进度（由 [syncImportProgress] 按 ViewModel 状态同步）。
     * 次操作「清空自定义频道」经二次确认后执行。
     */
    private fun showImportDialog() {
        if (importDialog?.isShowing == true) {
            return
        }
        val dialogBinding = DialogImportBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_title)
            .setView(dialogBinding.root)
            // 确认按钮点击行为在 onShow 中接管：导入期间保持对话框展示进度
            .setPositiveButton(R.string.import_confirm, null)
            .setNegativeButton(R.string.import_cancel, null)
            .setNeutralButton(R.string.remove_custom_channels, null)
            .create()
        importDialog = dialog
        importDialogBinding = dialogBinding
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                viewModel.importChannels(dialogBinding.importInputEdit.text?.toString().orEmpty())
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                confirmClearCustomChannels()
            }
            syncImportProgress(viewModel.importInFlight.value)
        }
        // 输入为空时禁用导入按钮；空白（含空格）视为未输入
        dialogBinding.importInputEdit.doOnTextChanged { text, _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                !text.isNullOrBlank() && !viewModel.importInFlight.value
        }
        dialog.show()
    }

    /**
     * 清空自定义频道二次确认：确认文案 remove_custom_confirm，确认后交由 ViewModel 执行。
     */
    private fun confirmClearCustomChannels() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.remove_custom_confirm)
            .setPositiveButton(R.string.remove_custom_channels) { _, _ ->
                viewModel.clearImportedChannels()
            }
            .setNegativeButton(R.string.import_cancel, null)
            .show()
    }

    /**
     * 关闭并释放导入对话框。
     */
    private fun dismissImportDialog() {
        importDialog?.takeIf { it.isShowing }?.dismiss()
        importDialog = null
        importDialogBinding = null
    }

    /**
     * 同步导入任务进行中状态到对话框：显示进度并禁用导入 / 清空按钮。
     */
    private fun syncImportProgress(inFlight: Boolean) {
        val dialog = importDialog?.takeIf { it.isShowing } ?: return
        val dialogBinding = importDialogBinding ?: return
        dialogBinding.importProgress.isVisible = inFlight
        val inputNotBlank = dialogBinding.importInputEdit.text?.isNotBlank() == true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !inFlight && inputNotBlank
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !inFlight
    }

    /**
     * 一次性事件处理：导入结果、收藏提示与清空结果均以 Snackbar 反馈；
     * 导入成功关闭导入对话框，失败保留输入内容便于重试。
     */
    private fun onBrowseEvent(event: BrowseEvent) {
        when (event) {
            is BrowseEvent.ImportSucceeded -> {
                dismissImportDialog()
                showSnackbar(getString(R.string.import_success_format, event.importedCount))
            }
            is BrowseEvent.ImportFailed -> {
                showSnackbar(getString(R.string.import_failed_format, getString(event.reasonRes)))
            }
            is BrowseEvent.FavoriteToggled -> {
                showSnackbar(
                    getString(if (event.added) R.string.favorite_added else R.string.favorite_removed)
                )
            }
            BrowseEvent.CustomChannelsCleared -> {
                showSnackbar(getString(R.string.remove_custom_done))
            }
        }
    }

    /**
     * 展示底部提示；导航栏避让由 Material Snackbar 的 edge-to-edge 处理承担。
     * 同一时刻只保留最新一条：先关闭旧提示再展示，快速连击星标不堆叠提示。
     */
    private fun showSnackbar(text: String) {
        currentSnackbar?.dismiss()
        currentSnackbar = Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).also { it.show() }
    }

    companion object {
        /** 沉浸式浮层表面不透明度：沿用 230/255 约定，仅作用于背景 drawable。 */
        private const val OVERLAY_SURFACE_ALPHA = 230

        /** RecyclerView 离屏缓存条目数（默认 2）：滚动回退时减少重绑定。 */
        private const val RECYCLER_CACHE_SIZE = 4

        /** 顶部避让类型：系统栏与显示裁切（挖孔）取并集。 */
        private val BAR_INSETS_MASK =
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

        /** 底部避让类型：在系统栏 / 挖孔基础上叠加输入法，取并集后自动得到较大者。 */
        private val BOTTOM_INSETS_MASK = BAR_INSETS_MASK or WindowInsetsCompat.Type.ime()
    }
}
