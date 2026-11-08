package com.sugar.tv.browse

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sugar.tv.R
import com.sugar.tv.databinding.ItemChannelBinding

/**
 * 频道列表适配器：ListAdapter + DiffUtil 增量更新，条目身份由
 * [DIFF] 的 areItemsTheSame 按频道 id 判定（不另设 RecyclerView 稳定 id：
 * 频道 id 为任意字符串，hashCode 生成的 32 位稳定 id 在大量频道下会碰撞）。
 *
 * 渲染与业务解耦：条目点击与收藏星操作通过回调交给页面（最终转交 ViewModel），
 * 本类不持有任何业务状态。
 *
 * @property onChannelClick 条目点击回调（跳转播放页）
 * @property onFavoriteClick 收藏星点击回调（乐观更新由 ViewModel 处理）
 */
class ChannelListAdapter(
    private val onChannelClick: (ChannelListItem) -> Unit,
    private val onFavoriteClick: (ChannelListItem) -> Unit,
) : ListAdapter<ChannelListItem, ChannelListAdapter.ChannelViewHolder>(DIFF) {

    init {
        // 异步目录尚未到达时延后恢复位置，避免空列表消费掉页面保存的滚动状态。
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding, onChannelClick, onFavoriteClick)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * 频道条目 ViewHolder：监听器只绑定一次，数据随 bind 幂等回填。
     *
     * @property binding 条目 ViewBinding
     */
    class ChannelViewHolder(
        private val binding: ItemChannelBinding,
        onChannelClick: (ChannelListItem) -> Unit,
        onFavoriteClick: (ChannelListItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 当前绑定的条目数据；监听器经它取值，避免按 position 捕获导致错位。 */
        private var currentItem: ChannelListItem? = null

        init {
            binding.root.setOnClickListener { currentItem?.let(onChannelClick) }
            binding.channelFavoriteStar.setOnClickListener { view ->
                // 收藏是轻量高频操作：触感反馈确认「已点到」，星标自身动画由 selected 态承担
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                currentItem?.let(onFavoriteClick)
            }
        }

        /**
         * 绑定条目与收藏操作描述，读屏在星标上也能区分所操作的频道。
         */
        fun bind(item: ChannelListItem) {
            currentItem = item
            val context = binding.root.context
            binding.channelName.text = item.name
            binding.channelMetadata.text = context.getString(
                R.string.channel_metadata_format,
                context.getString(item.categoryLabelRes),
                context.getString(R.string.source_count_format, item.sourceCount),
            )
            binding.channelFavoriteStar.isSelected = item.isFavorite
            binding.channelFavoriteStar.contentDescription = context.getString(
                R.string.channel_favorite_description_format,
                item.name,
                context.getString(if (item.isFavorite) R.string.favorite_remove else R.string.favorite_add),
            )
        }
    }

    private companion object {
        /**
         * DiffUtil 判定：条目身份按频道 id，内容按全字段相等（data class equals）。
         */
        val DIFF = object : DiffUtil.ItemCallback<ChannelListItem>() {
            override fun areItemsTheSame(oldItem: ChannelListItem, newItem: ChannelListItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChannelListItem, newItem: ChannelListItem): Boolean =
                oldItem == newItem
        }
    }
}
