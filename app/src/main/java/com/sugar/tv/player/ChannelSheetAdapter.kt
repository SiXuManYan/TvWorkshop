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
import com.sugar.tv.databinding.ItemSheetChannelBinding

/**
 * 频道抽屉列表行：频道 + 当前播放高亮标记。
 *
 * 高亮标记参与 DiffUtil 比较，当前播放项变化时仅重绑受影响行，无需全量刷新。
 *
 * @property channel 频道数据
 * @property isPlaying 是否为当前播放频道
 */
data class ChannelSheetRow(
    val channel: Channel,
    val isPlaying: Boolean,
)

/** 行级 Diff：以频道 id 为身份，内容相等性包含高亮标记。 */
private val CHANNEL_SHEET_DIFF = object : DiffUtil.ItemCallback<ChannelSheetRow>() {
    override fun areItemsTheSame(oldItem: ChannelSheetRow, newItem: ChannelSheetRow): Boolean =
        oldItem.channel.id == newItem.channel.id

    override fun areContentsTheSame(oldItem: ChannelSheetRow, newItem: ChannelSheetRow): Boolean =
        oldItem == newItem
}

/**
 * 频道抽屉列表适配器：绑定频道名并高亮当前播放项。
 *
 * @param onChannelClick 点选频道回调，由抽屉转交给共享 ViewModel
 */
class ChannelSheetAdapter(
    private val onChannelClick: (Channel) -> Unit,
) : ListAdapter<ChannelSheetRow, ChannelSheetAdapter.ChannelViewHolder>(CHANNEL_SHEET_DIFF) {

    init {
        // 目录异步到达前先保留系统保存的滚动位置。
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemSheetChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding, onChannelClick)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** 列表项 ViewHolder：激活态驱动高亮背景，播放图标仅占位不参与无障碍朗读。 */
    class ChannelViewHolder(
        private val binding: ItemSheetChannelBinding,
        onChannelClick: (Channel) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var row: ChannelSheetRow? = null

        init {
            binding.sheetItemRoot.setOnClickListener {
                row?.channel?.let(onChannelClick)
            }
        }

        /** 绑定高亮、图标与读屏状态；非播放项的图标以 INVISIBLE 占位，保持对齐。 */
        fun bind(item: ChannelSheetRow) {
            row = item
            binding.sheetItemRoot.isActivated = item.isPlaying
            ViewCompat.setStateDescription(
                binding.sheetItemRoot,
                if (item.isPlaying) binding.root.context.getString(R.string.channel_now_playing) else null,
            )
            binding.sheetItemPlayingIcon.visibility =
                if (item.isPlaying) View.VISIBLE else View.INVISIBLE
            binding.sheetItemName.text = item.channel.name
        }
    }
}
