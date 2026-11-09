package com.sugar.tv.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sugar.tv.R
import com.sugar.tv.data.model.Channel
import com.sugar.tv.databinding.ItemPanelChannelBinding

/**
 * 频道面板列表行：频道 + 当前播放高亮标记。
 *
 * 高亮标记参与 DiffUtil 比较，当前播放项变化时仅重绑受影响行，无需全量刷新。
 *
 * @property channel 频道数据
 * @property isPlaying 是否为当前播放频道
 */
data class ChannelPanelRow(
    val channel: Channel,
    val isPlaying: Boolean,
)

/** 行级 Diff：以频道 id 为身份，内容相等性包含高亮标记。 */
private val CHANNEL_PANEL_DIFF = object : DiffUtil.ItemCallback<ChannelPanelRow>() {
    override fun areItemsTheSame(oldItem: ChannelPanelRow, newItem: ChannelPanelRow): Boolean =
        oldItem.channel.id == newItem.channel.id

    override fun areContentsTheSame(oldItem: ChannelPanelRow, newItem: ChannelPanelRow): Boolean =
        oldItem == newItem
}

/**
 * 频道面板列表适配器：绑定频道名并高亮当前播放项。
 *
 * @param onChannelClick 点选频道回调，由页面转交给播放 ViewModel
 */
class ChannelPanelAdapter(
    private val onChannelClick: (Channel) -> Unit,
) : ListAdapter<ChannelPanelRow, ChannelPanelAdapter.ChannelViewHolder>(CHANNEL_PANEL_DIFF) {

    init {
        // 目录异步到达前先保留系统保存的滚动位置。
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    /** 使用面板深色主题创建行，监听只在 Holder 创建时绑定一次。 */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemPanelChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding = binding, onChannelClick = onChannelClick)
    }

    /** 由当前快照绝对回填，避免回收后残留其他频道的播放高亮。 */
    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * 激活态驱动高亮背景，播放图标仅占位不参与无障碍朗读。
     *
     * @param binding 列表行视图
     * @param onChannelClick 只绑定一次的点击回调
     */
    class ChannelViewHolder(
        private val binding: ItemPanelChannelBinding,
        onChannelClick: (Channel) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 最近一次绑定的行，点击时读取最新频道。 */
        private var row: ChannelPanelRow? = null

        init {
            binding.panelItemRoot.setOnClickListener {
                row?.channel?.let(onChannelClick)
            }
        }

        /** 绑定高亮、图标与读屏状态；非播放项的图标以 INVISIBLE 占位，保持对齐。 */
        fun bind(item: ChannelPanelRow) {
            row = item
            binding.panelItemRoot.isActivated = item.isPlaying
            ViewCompat.setStateDescription(
                binding.panelItemRoot,
                if (item.isPlaying) binding.root.context.getString(R.string.channel_now_playing) else null,
            )
            binding.panelItemPlayingIcon.visibility =
                if (item.isPlaying) View.VISIBLE else View.INVISIBLE
            binding.panelItemName.text = item.channel.name
        }
    }
}
