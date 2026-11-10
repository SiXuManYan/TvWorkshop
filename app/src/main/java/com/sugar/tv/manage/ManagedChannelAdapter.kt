package com.sugar.tv.manage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sugar.tv.R
import com.sugar.tv.browse.labelRes
import com.sugar.tv.data.model.Channel
import com.sugar.tv.databinding.ItemManagedChannelBinding

/**
 * 管理页频道条目，只显示名称与线路数，避免在公共界面暴露完整地址或令牌。
 *
 * @property onPlay 频道播放回调
 * @property onRemove 单个删除回调，由页面进行确认
 */
class ManagedChannelAdapter(
    private val onPlay: (Channel) -> Unit,
    private val onRemove: (Channel) -> Unit,
) : ListAdapter<Channel, ManagedChannelAdapter.Holder>(DIFF) {

    init {
        // 首次数据库结果可能合法为空，页面在首次提交完成后明确放行恢复。
        stateRestorationPolicy = StateRestorationPolicy.PREVENT
    }

    /** 创建监听器只绑定一次的频道行。 */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        binding = ItemManagedChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        onPlay = onPlay,
        onRemove = onRemove,
    )

    /** 使用不可变频道快照回填条目。 */
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    /**
     * 保留当前条目而不捕获列表位置，异步 Diff 后点击仍对应正确频道。
     *
     * @property binding 当前行的视图绑定
     * @param onPlay 播放操作
     * @param onRemove 删除操作
     */
    class Holder(
        private val binding: ItemManagedChannelBinding,
        onPlay: (Channel) -> Unit,
        onRemove: (Channel) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 监听器始终读取最新绑定的频道。 */
        private var channel: Channel? = null

        init {
            binding.root.setOnClickListener { channel?.let(onPlay) }
            binding.managedDelete.setOnClickListener { channel?.let(onRemove) }
        }

        /** 条目内容与读屏描述均按绝对值回填。 */
        fun bind(value: Channel) {
            channel = value
            val context = binding.root.context
            binding.managedName.text = value.name
            binding.managedMetadata.text = context.getString(
                R.string.channel_metadata_format,
                context.getString(value.category.labelRes()),
                context.getString(R.string.source_count_format, value.sources.size),
            )
            binding.managedDelete.contentDescription = context.getString(
                R.string.manager_delete_description_format,
                value.name,
            )
        }
    }

    private companion object {
        /** id 确定身份，所有显示字段参与内容比较。 */
        val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            /** 同名的不同导入记录仍以 id 区分。 */
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.id == newItem.id

            /** 线路数或名称改变时重新回填显示内容。 */
            override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
        }
    }
}
