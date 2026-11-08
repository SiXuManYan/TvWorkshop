package com.sugar.tv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sugar.tv.R
import com.sugar.tv.data.model.Channel
import com.sugar.tv.databinding.SheetPlayerChannelsBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 播放页频道列表抽屉：顶部名称过滤，当前播放频道高亮；点选频道切换播放并关闭抽屉。
 *
 * 与播放页共享 [PlayerViewModel]（Activity 作用域），自身只转交过滤词并渲染；
 * 系统导航栏 insets 由 BottomSheetDialog 自身处理，不再重复避让。
 */
class PlayerChannelsSheetFragment : BottomSheetDialogFragment() {

    /** 视图生命周期内的绑定引用，销毁视图时主动清除。 */
    private var _binding: SheetPlayerChannelsBinding? = null

    /** 抽屉视图绑定；仅 在 onCreateView 与 onDestroyView 之间可用。 */
    private val binding: SheetPlayerChannelsBinding get() = _binding!!

    /** 与播放页共享的播放 ViewModel，提供频道全集与当前播放态。 */
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(requireActivity())[PlayerViewModel::class.java]
    }

    /** 当前抽屉视图的列表适配器，关闭时解除 RecyclerView 关联。 */
    private lateinit var adapter: ChannelSheetAdapter

    /** 当前过滤关键词（trim 后，空串表示不过滤）。 */
    private val query = MutableStateFlow("")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetPlayerChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChannelSheetAdapter(onChannelClick = ::onChannelClick)
        binding.sheetChannelRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.sheetChannelRecycler.adapter = adapter
        binding.sheetSearchEdit.doAfterTextChanged { text ->
            query.value = text?.toString()?.trim().orEmpty()
        }
        observeViewModel()
    }

    /** 仅在视图 STARTED 期间收集合成结果，过滤与行映射在 ViewModel 的 Default 调度器执行。 */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.channelSheetRows(query).collect(::renderRows)
            }
        }
    }

    /** 渲染已过滤且去重的行列表与空态，不在主线程遍历频道全集。 */
    private fun renderRows(rows: List<ChannelSheetRow>) {
        adapter.submitList(rows)
        val emptyTextRes = when {
            rows.isEmpty() && viewModel.allChannels.value.isEmpty() -> R.string.empty_channel_list
            rows.isEmpty() -> R.string.empty_search_result
            else -> 0
        }
        binding.sheetEmptyText.isVisible = emptyTextRes != 0
        if (emptyTextRes != 0) {
            binding.sheetEmptyText.setText(emptyTextRes)
        }
    }

    /** 点选频道：切换播放并关闭抽屉；点选当前播放频道不重新起播（ViewModel 内去重）。 */
    private fun onChannelClick(channel: Channel) {
        viewModel.loadChannel(channel.id)
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() {
        _binding?.sheetChannelRecycler?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        /** 抽屉事务标签。 */
        const val TAG = "PlayerChannelsSheetFragment"
    }
}
