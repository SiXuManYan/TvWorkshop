package com.sugar.tv.browse

import androidx.annotation.StringRes
import com.sugar.tv.R
import com.sugar.tv.data.model.ChannelCategory

/**
 * 浏览页分类：在频道实体分类之上增加「最近观看」聚合入口。
 *
 * @param labelRes 分类 Chip 展示文案资源
 * @param channelCategory 对应的频道实体分类；[RECENT] 为观看记录聚合，无对应实体分类
 */
enum class BrowseCategory(
    @StringRes val labelRes: Int,
    val channelCategory: ChannelCategory?,
) {
    /** 最近观看：按观看记录顺序映射全部分类的频道。 */
    RECENT(R.string.category_recent, null),

    /** 央视。 */
    CCTV(R.string.category_cctv, ChannelCategory.CCTV),

    /** 卫视。 */
    SATELLITE(R.string.category_satellite, ChannelCategory.SATELLITE),

    /** 外国频道。 */
    FOREIGN(R.string.category_foreign, ChannelCategory.FOREIGN),

    /** 自定义（用户导入）。 */
    CUSTOM(R.string.category_custom, ChannelCategory.CUSTOM),
}

/**
 * 空态原因：列表为空时向 UI 下发的文案来源。
 *
 * [FAVORITES] 为收藏视图预留分支；当前分类契约中无收藏入口（Chip 仅有五类），
 * 该原因暂不可达，保留以覆盖字符串契约中的 empty_favorites。
 */
enum class EmptyReason(@StringRes val messageRes: Int) {
    /** 有搜索词但无匹配频道。 */
    SEARCH(R.string.empty_search_result),

    /** 收藏视图为空（预留）。 */
    FAVORITES(R.string.empty_favorites),

    /** 当前分类下没有频道（含最近观看无记录）。 */
    LIST(R.string.empty_channel_list),
}

/**
 * 频道列表条目的渲染模型：由频道实体与收藏状态合成，供 ListAdapter + DiffUtil 使用。
 *
 * @param id 频道唯一标识（DiffUtil 条目身份）
 * @param name 频道展示名称
 * @param categoryLabelRes 频道自身分类的展示文案资源
 * @param sourceCount 信号源数量
 * @param isFavorite 当前是否已收藏（含乐观更新后的本地状态）
 */
data class ChannelListItem(
    val id: String,
    val name: String,
    @StringRes val categoryLabelRes: Int,
    val sourceCount: Int,
    val isFavorite: Boolean,
)

/**
 * 浏览页不可变 UI 状态，经 StateFlow 只读暴露。
 *
 * @property loading 首次数据是否尚未到达（首数据到达即结束）
 * @property selectedCategory 当前选中的分类
 * @property query 已生效的搜索词（去空格后的 debounce 结果）
 * @property channels 当前分类与搜索词过滤后的频道列表
 * @property favorites 当前生效的收藏 id 集合（含乐观更新覆盖）
 * @property emptyReason 列表为空时的空态原因；列表非空时为 `null`
 */
data class BrowseUiState(
    val loading: Boolean = true,
    val selectedCategory: BrowseCategory = BrowseCategory.CCTV,
    val query: String = "",
    val channels: List<ChannelListItem> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val emptyReason: EmptyReason? = null,
)

/**
 * 浏览页一次性提示事件，经 Channel 派发，由页面在 STARTED 期间收集并展示。
 */
sealed interface BrowseEvent {
    /** 导入成功：携带成功导入的频道数量。 */
    data class ImportSucceeded(val importedCount: Int) : BrowseEvent

    /** 导入失败：携带失败原因文案资源（格式错误 / 网络不可用 / 通用失败）。 */
    data class ImportFailed(@StringRes val reasonRes: Int) : BrowseEvent

    /** 收藏状态切换成功：携带切换后的目标状态。 */
    data class FavoriteToggled(val added: Boolean) : BrowseEvent

    /** 自定义频道清空完成。 */
    data object CustomChannelsCleared : BrowseEvent
}

/**
 * 频道实体分类对应的展示文案资源（列表条目分类小标签）。
 */
fun ChannelCategory.labelRes(): Int = when (this) {
    ChannelCategory.CCTV -> R.string.category_cctv
    ChannelCategory.SATELLITE -> R.string.category_satellite
    ChannelCategory.FOREIGN -> R.string.category_foreign
    ChannelCategory.CUSTOM -> R.string.category_custom
}
