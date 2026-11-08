package com.sugar.tv.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 验证导入冲突在数据库内跳过，既有频道内容与顺序不因重复导入被改写。 */
@RunWith(AndroidJUnit4::class)
class ImportedChannelDaoTest {

    /** 每个测试独立的内存数据库，避免修改设备上的真实收藏与频道。 */
    private lateinit var database: AppDatabase

    /** 为真实 Room DAO 创建隔离的数据库。 */
    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    /** 测试结束后释放数据库线程和连接。 */
    @After
    fun closeDatabase() {
        database.close()
    }

    /** 重复 id 返回 -1，实际新增数应与结果一致，旧线路和 rowid 顺序保持原样。 */
    @Test
    fun insertAll_ignoresExistingIdsAndReportsActualInsertions() = runBlocking {
        val dao = database.importedChannelDao()
        val first = entity(id = "m3u:first", name = "原频道")
        val second = entity(id = "m3u:second", name = "后导入频道")
        dao.insertAll(listOf(first, second))

        val third = entity(id = "m3u:third", name = "新增频道")
        val results = dao.insertAll(
            listOf(first.copy(name = "不应覆盖"), third, third.copy(name = "不应重复")),
        )

        assertEquals(-1L, results[0])
        assertEquals(-1L, results[2])
        assertEquals(1, results.count { it != -1L })
        assertEquals(listOf(first, second, third), dao.observeAll().first())
    }

    /** 创建固定测试实体；数据内容本身不发起网络请求。 */
    private fun entity(id: String, name: String): ImportedChannelEntity = ImportedChannelEntity(
        id = id,
        name = name,
        groupName = null,
        sourcesJson = """[{"label":"主源","url":"https://example.com/live.m3u8"}]""",
    )
}
