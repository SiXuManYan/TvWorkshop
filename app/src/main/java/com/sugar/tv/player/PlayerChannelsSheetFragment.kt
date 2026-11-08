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
import kotlinx.coroutines.launch

/**
 * 播放页频道列表抽屉：顶部名称过滤，当前播放频道高亮；点选频道切换播放并关闭抽屉。
 *
 * 与播放页共享 [PlayerViewModel]（Activity 作用域），自身只做渲染与本地过滤；
 * 系统导航栏 insets 由 BottomSheetDialog 自身处理，不再重复避让。
 */
class PlayerChannelsSheetFragment : BottomSheetDialogFragment() {

    private var _binding: SheetPlayerChannelsBinding? = null

    /** 抽屉视图绑定；仅 在 onCreateView 与 onDestroyView 之间可用。 */
    private val binding: SheetPlayerChannelsBinding get() = _binding!!

    /** 与播放页共享的播放 ViewModel，提供频道全集与当前播放态。 */
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(requireActivity())[PlayerViewModel::class.java]
    }

    private lateinit var adapter: ChannelSheetAdapter

    /** 频道全集快照，用于本地名称过滤。 */
    private var allChannels: List<Channel> = emptyList()

    /** 当前过滤关键词（trim 后，空串表示不过滤）。 */
    private var query: String = ""

    /** 最近一次渲染的当前播放频道 id，播放项变化时才重建列表。 */
    private var lastCurrentChannelId: String? = null

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
            query = text?.toString()?.trim().orEmpty()
            refreshRows()
        }
        observeViewModel()
    }

    /** 收集共享 ViewModel 的频道全集与播放态，驱动列表与高亮更新。 */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allChannels.collect { channels ->
                        allChannels = channels
                        refreshRows()
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        val playingId = state.channel?.id
                        if (playingId != lastCurrentChannelId) {
                            lastCurrentChannelId = playingId
                            refreshRows()
                        }
                    }
                }
            }
        }
    }

    /** 依据全集、过滤词与当前播放项重建行数据，交由 ListAdapter 做最小 diff 更新。 */
    private fun refreshRows() {
        val filtered = if (query.isEmpty()) {
            allChannels
        } else {
            allChannels.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered.map { ChannelSheetRow(it, isPlaying = it.id == lastCurrentChannelId) })
        val emptyTextRes = when {
            filtered.isEmpty() && allChannels.isEmpty() -> R.string.empty_channel_list
            filtered.isEmpty() -> R.string.empty_search_result
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
