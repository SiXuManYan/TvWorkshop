package com.sugar.tv.manage

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sugar.tv.R
import com.sugar.tv.base.BaseActivity
import com.sugar.tv.data.model.Channel
import com.sugar.tv.databinding.ActivityChannelManagerBinding
import com.sugar.tv.player.PlayerActivity
import kotlinx.coroutines.launch

/** 独立的频道与记录管理页；文件选择、确认窗口和导航归页面，业务写入归 ViewModel。 */
class ChannelManagerActivity : BaseActivity() {

    /** 仅在 Context 挂载后创建视图。 */
    private lateinit var binding: ActivityChannelManagerBinding

    /** 频道行由 DiffUtil 更新，操作头部独立渲染。 */
    private lateinit var channelAdapter: ManagedChannelAdapter

    /** 可滚动的操作卡片不会挤掉短窗口里的频道列表。 */
    private lateinit var headerAdapter: ChannelManagerHeaderAdapter

    /** 管理页状态在重建后保留，仓库持有文件 IO。 */
    private val viewModel: ChannelManagerViewModel by lazy {
        ViewModelProvider(this)[ChannelManagerViewModel::class.java]
    }

    /** 同一时刻只持有一个确认窗口，销毁时解除。 */
    private var confirmation: AlertDialog? = null

    /** 新结果替换旧提示，避免连续操作产生长提示队列。 */
    private var snackbar: Snackbar? = null

    /** 系统文件授权按次使用，取消选择不改变任何数据库状态。 */
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importDocument(uri = uri)
    }

    /** 通过系统创建独立文件；不申请共享存储权限，也不导出媒体。 */
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        if (uri != null) viewModel.exportDocument(uri = uri)
    }

    /** 装配限宽页面、系统栏避让和列表，恢复操作由各自 ViewModel 与 RecyclerView 承担。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.managerToolbar)
        binding.managerToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        setupInsets()
        setupList()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch {
                    viewModel.notice.collect { notice ->
                        showNotice(
                            notice.count?.let { getString(notice.messageRes, it) } ?: getString(notice.messageRes),
                        )
                    }
                }
            }
        }
    }

    /** 搜索入口固定在工具栏，列表头部随内容滚动。 */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_channel_manager, menu)
        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as? SearchView ?: return true
        searchView.queryHint = getString(R.string.search_hint)
        val query = viewModel.uiState.value.query
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, false)
            searchView.clearFocus()
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            /** 提交后收起键盘，保留当前查询。 */
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.search(value = query.orEmpty())
                searchView.clearFocus()
                return true
            }

            /** 筛选在后台执行，不在回调里读库或解析频道。 */
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.search(value = newText.orEmpty())
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            /** 允许系统正常展开搜索操作区。 */
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            /** 关闭搜索同时恢复完整导入列表。 */
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.search(value = "")
                return true
            }
        })
        return true
    }

    /** 根布局独占系统栏、挖孔和键盘避让，子列表不重复加 Insets。 */
    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(left = safe.left, top = safe.top, right = safe.right, bottom = safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /** 列表头部与频道复用同一滚动容器，零频道时仍能导入文件或清空历史。 */
    private fun setupList() {
        channelAdapter = ManagedChannelAdapter(
            onPlay = { channel ->
                if (!viewModel.uiState.value.busy) {
                    startActivity(PlayerActivity.createIntent(context = this, channelId = channel.id))
                }
            },
            onRemove = ::confirmRemove,
        )
        headerAdapter = ChannelManagerHeaderAdapter(
            onImport = ::chooseImportFile,
            onExport = ::confirmExport,
            onClearHistory = ::confirmClearHistory,
        )
        binding.managerList.apply {
            layoutManager = LinearLayoutManager(this@ChannelManagerActivity)
            adapter = ConcatAdapter(headerAdapter, channelAdapter)
            itemAnimator = null
            setHasFixedSize(true)
        }
    }

    /** 相等列表不重复提交；搜索和后台写入不会重置滚动位置。 */
    private fun render(state: ChannelManagerUiState) {
        headerAdapter.render(state = state)
        if (channelAdapter.currentList != state.channels) {
            channelAdapter.submitList(state.channels) {
                channelAdapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.ALLOW
            }
        } else if (!state.loading) {
            // 零频道时头部仍可能很长，首个合法空结果也必须恢复头部的滚动位置。
            channelAdapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.ALLOW
        }
    }

    /** 兼容未声明 m3u MIME 的文件提供器，文件内容交解析器验证。 */
    private fun chooseImportFile() {
        if (viewModel.uiState.value.busy) return
        try {
            openDocument.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            showNotice(getString(R.string.manager_picker_unavailable))
        }
    }

    /** 导出包含完整地址，先说明凭据与保存位置的边界。 */
    private fun confirmExport() {
        if (viewModel.uiState.value.busy || viewModel.uiState.value.totalCount == 0) return
        showConfirmation(
            title = getString(R.string.manager_export),
            message = getString(R.string.manager_export_notice),
            action = getString(R.string.manager_export),
        ) {
            try {
                createDocument.launch("tv-workshop-channels.m3u")
            } catch (_: ActivityNotFoundException) {
                showNotice(getString(R.string.manager_picker_unavailable))
            }
        }
    }

    /** 删除只针对这一个导入身份，同名内置频道和其他导入记录不受影响。 */
    private fun confirmRemove(channel: Channel) {
        if (viewModel.uiState.value.busy) return
        showConfirmation(
            title = getString(R.string.manager_delete),
            message = getString(R.string.manager_delete_confirm_format, channel.name),
            action = getString(R.string.manager_delete),
        ) { viewModel.removeChannel(channelId = channel.id) }
    }

    /** 观看记录清理必须主动确认，空记录或已有写入时不重复启动。 */
    private fun confirmClearHistory() {
        if (viewModel.uiState.value.busy || viewModel.uiState.value.recentCount == 0) return
        showConfirmation(
            title = getString(R.string.manager_clear_history),
            message = getString(R.string.manager_clear_history_confirm),
            action = getString(R.string.manager_clear_history),
        ) { viewModel.clearHistory() }
    }

    /** 所有确认窗口由页面持有，只有点击主操作才执行回调。 */
    private fun showConfirmation(title: String, message: String, action: String, onConfirm: () -> Unit) {
        if (confirmation?.isShowing == true) return
        confirmation = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { if (confirmation === dialog) confirmation = null }
                dialog.show()
            }
    }

    /** 提示不包含 URI、地址或异常详情，避免泄露列表内容。 */
    private fun showNotice(message: String) {
        snackbar?.dismiss()
        snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).also { it.show() }
    }

    /** 关闭页面窗口并解除 Adapter，文件任务不捕获 Activity。 */
    override fun onDestroy() {
        confirmation?.dismiss()
        confirmation = null
        snackbar?.dismiss()
        snackbar = null
        binding.managerList.adapter = null
        super.onDestroy()
    }
}
