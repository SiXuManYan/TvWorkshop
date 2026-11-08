package com.sugar.tv.data.db

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 收藏频道记录：仅存频道 id 与收藏时间，频道本体始终来自内置目录或导入表。
 *
 * @property channelId 频道唯一标识（内置 `builtin:` 或导入 `m3u:` 前缀），主键
 * @property addedAt 收藏时间（epoch 毫秒）
 */
@Keep
@Entity(tableName = "favorite_channels")
data class FavoriteChannelEntity(
    @PrimaryKey val channelId: String,
    val addedAt: Long,
)

/**
 * 最近观看记录：仅存频道 id 与最近一次观看时间，同一频道重复观看时覆盖更新。
 *
 * @property channelId 频道唯一标识，主键
 * @property watchedAt 最近观看时间（epoch 毫秒）
 */
@Keep
@Entity(tableName = "recent_channels")
data class RecentChannelEntity(
    @PrimaryKey val channelId: String,
    val watchedAt: Long,
)

/**
 * 通过 m3u 订阅导入的自定义频道。
 *
 * 频道 id 由名称与首个 URL 的 SHA-256 派生（见 [com.sugar.tv.data.m3u.M3uParser]），
 * 因此同名同源的重复导入天然幂等。
 *
 * @property id 导入频道唯一标识（`m3u:` 前缀），主键
 * @property name 频道名称（取自 #EXTINF 逗号后的频道名或裸 URL 推导名）
 * @property groupName m3u 中的分组名（group-title），分类在读取时按映射规则重新推导
 * @property sourcesJson 信号源列表的 JSON 序列化结果，格式见
 *   [com.sugar.tv.data.SourceCodec]
 */
@Keep
@Entity(tableName = "imported_channels")
data class ImportedChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val groupName: String?,
    val sourcesJson: String,
)
