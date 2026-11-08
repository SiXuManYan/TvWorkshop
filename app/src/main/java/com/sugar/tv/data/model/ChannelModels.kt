package com.sugar.tv.data.model

import androidx.annotation.Keep

/**
 * 频道分类。
 *
 * 作为业务与持久化都使用的稳定枚举，值顺序不可随意调整。
 */
@Keep
enum class ChannelCategory {
    /** 央视频道。 */
    CCTV,

    /** 卫视频道。 */
    SATELLITE,

    /** 外国频道。 */
    FOREIGN,

    /** 用户通过导入获得的自定义频道。 */
    CUSTOM,
}

/**
 * 频道的一路播放信号源。
 *
 * @param label 信号源展示名称，如「线路一」
 * @param url 信号源播放地址（m3u / m3u8 或直链流地址）
 */
@Keep
data class ChannelSource(
    val label: String,
    val url: String,
)

/**
 * 频道实体：包含展示信息与可切换的多路信号源。
 *
 * @param id 频道唯一标识，持久化与收藏、观看记录均以此为准
 * @param name 频道展示名称
 * @param category 所属分类
 * @param sources 有序信号源列表，播放失败时按下标顺序切换
 */
@Keep
data class Channel(
    val id: String,
    val name: String,
    val category: ChannelCategory,
    val sources: List<ChannelSource>,
)
