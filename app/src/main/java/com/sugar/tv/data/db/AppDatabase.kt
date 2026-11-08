package com.sugar.tv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 频道数据 Room 数据库：承载收藏、最近观看与导入频道三张表。
 *
 * 由 [com.sugar.tv.data.ChannelRepository] 以单例方式持有，页面与 ViewModel 不直接访问。
 */
@Database(
    entities = [
        FavoriteChannelEntity::class,
        RecentChannelEntity::class,
        ImportedChannelEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    /** 收藏频道数据访问对象。 */
    abstract fun favoriteChannelDao(): FavoriteChannelDao

    /** 最近观看数据访问对象。 */
    abstract fun recentChannelDao(): RecentChannelDao

    /** 导入频道数据访问对象。 */
    abstract fun importedChannelDao(): ImportedChannelDao

    companion object {
        /** 数据库文件名，仓库层据此构建单例。 */
        const val DATABASE_NAME: String = "tv_workshop.db"
    }
}
