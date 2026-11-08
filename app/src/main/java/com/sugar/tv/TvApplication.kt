package com.sugar.tv

import android.app.Application
import com.sugar.tv.data.ChannelRepository

/**
 * 应用入口：持有 Application 级依赖，供各页面与 ViewModel 获取。
 */
class TvApplication : Application() {

    /** 全局唯一的频道数据仓库，首次访问时创建。 */
    val channelRepository: ChannelRepository by lazy { ChannelRepository(this) }
}
