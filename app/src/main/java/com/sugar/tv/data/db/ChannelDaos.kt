package com.sugar.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 收藏频道表的数据访问：提供流式观察与增删查。 */
@Dao
interface FavoriteChannelDao {

    /** 观察全部收藏频道 id，按收藏时间倒序（最新收藏在前）。 */
    @Query("SELECT channelId FROM favorite_channels ORDER BY addedAt DESC")
    fun observeFavoriteIds(): Flow<List<String>>

    /** 查询单个收藏记录；未收藏时返回 `null`。 */
    @Query("SELECT * FROM favorite_channels WHERE channelId = :channelId")
    suspend fun getById(channelId: String): FavoriteChannelEntity?

    /** 新增收藏；同一频道主键冲突时以新时间覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteChannelEntity)

    /** 取消收藏指定频道。 */
    @Query("DELETE FROM favorite_channels WHERE channelId = :channelId")
    suspend fun deleteById(channelId: String)

    /**
     * 原子切换收藏状态：未收藏则写入、已收藏则移除，读改写收敛在单个事务内，
     * 并发调用不会交错导致切换丢失；返回切换后的收藏状态（`true` = 已收藏）。
     */
    @Transaction
    suspend fun toggle(channelId: String, addedAt: Long): Boolean {
        if (getById(channelId) == null) {
            insert(FavoriteChannelEntity(channelId = channelId, addedAt = addedAt))
            return true
        }
        deleteById(channelId)
        return false
    }
}

/** 最近观看表的数据访问：提供流式观察与覆盖式写入。 */
@Dao
interface RecentChannelDao {

    /** 观察全部最近观看频道 id，按观看时间倒序（最近观看在前）。 */
    @Query("SELECT channelId FROM recent_channels ORDER BY watchedAt DESC")
    fun observeRecentIds(): Flow<List<String>>

    /** 记录一次观看；同一频道已存在记录时覆盖为最新时间。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentChannelEntity)
}

/** 导入频道表的数据访问：提供流式观察、批量写入与删除。 */
@Dao
interface ImportedChannelDao {

    /** 观察全部导入频道，按导入先后稳定排序。 */
    @Query("SELECT * FROM imported_channels ORDER BY rowid ASC")
    fun observeAll(): Flow<List<ImportedChannelEntity>>

    /** 查询单个导入频道；不存在时返回 `null`。 */
    @Query("SELECT * FROM imported_channels WHERE id = :id")
    suspend fun getById(id: String): ImportedChannelEntity?

    /** 批量插入并保留已有同 id 频道；返回 rowid，主键冲突跳过的元素为 -1。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<ImportedChannelEntity>): List<Long>

    /** 删除单个导入频道，返回删除的行数。 */
    @Query("DELETE FROM imported_channels WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** 清空全部导入频道，返回被清空的行数。 */
    @Query("DELETE FROM imported_channels")
    suspend fun deleteAll(): Int
}
