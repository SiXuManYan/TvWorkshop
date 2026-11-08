package com.sugar.tv.data.builtin

import com.sugar.tv.data.model.Channel
import com.sugar.tv.data.model.ChannelCategory
import com.sugar.tv.data.model.ChannelSource

/**
 * 内置频道目录：随应用分发的默认频道集合。
 *
 * 原目录注释记载整理日期为 2026-11-08，参考列表与本次可复核范围如下：
 * - `https://github.com/fanmingming/live` 的 `tv/m3u/ipv6.m3u`：18 个 CCTV 主源
 *   （IPv6，应用省略上游末尾的空 `?`）及 6 个 CGTN 主源（HTTPS 域名）。
 * - `https://github.com/iptv-org/iptv` 的 cn / au / news 列表与韩国原始流文件：
 *   部分国内频道、CGTN 备源及外国频道地址。
 * - `https://github.com/Guovin/iptv-api` 的历史结果：部分地址仍可复核；
 *   当前上游为工具框架，旧输出链接已停止更新。
 *
 * 当前共 66 个频道、169 个地址；2026-11-08 文本比对命中 81 个，88 个待补逐条出处，
 * 固定文件版本与核对范围见 `project-docs/TECHNICAL_DESIGN.md` 第 5 节。
 * 地址随应用发布，不自动同步上游；列表整理者不等于实际流服务或节目权利方。
 * 可用性与内容授权未逐条验证；播放报错后按 sources 顺序尝试后续线路。
 * 频道名称属于数据而非界面文案，保持频道官方命名，不参与多语言。
 */
object BuiltinChannels {

    /** 全部内置频道，顺序即界面默认展示顺序：央视 → 卫视 → 外国。 */
    val all: List<Channel> = listOf(
        // ==================== 央视 ====================
        channel(
            "builtin:cctv-1", "CCTV-1 综合", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226895/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv1hd.m3u8",
            "备用2" to "http://38.75.136.137:98/gslb/dsdqbv/cctv1hd.m3u8?auth=test20251009",
        ),
        channel(
            "builtin:cctv-2", "CCTV-2 财经", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226893/1.m3u8",
            "备用1" to "http://198.204.228.26/live/cctv2hd.m3u8",
            "备用2" to "http://107.150.60.122/live/cctv2hd.m3u8",
        ),
        channel(
            "builtin:cctv-3", "CCTV-3 综艺", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226471/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv3hd.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv3hd",
        ),
        channel(
            "builtin:cctv-4", "CCTV-4 中文国际", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226335/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv4hd.m3u8",
            "备用2" to "http://107.150.60.122/live/cctv4hd.m3u8",
        ),
        channel(
            "builtin:cctv-5", "CCTV-5 体育", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226469/1.m3u8",
            "备用1" to "http://107.150.60.122/live/cctv5hd.m3u8",
            "备用2" to "http://173.208.212.130:8181/720p/cctv5.m3u8",
        ),
        channel(
            "builtin:cctv-5-plus", "CCTV-5+ 体育赛事", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226894/1.m3u8",
            "备用1" to "http://38.75.136.137:98/gslb/dsdqbv/cctv5p.m3u8?auth=test20251009",
            "备用2" to "https://myip.pdtvhd.com/Sports/streams/CCTV5pul.m3u8",
        ),
        channel(
            "builtin:cctv-6", "CCTV-6 电影", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226468/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv6hd.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv6hd",
        ),
        channel(
            "builtin:cctv-7", "CCTV-7 国防军事", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226946/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv7hd.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv7hd",
        ),
        channel(
            "builtin:cctv-8", "CCTV-8 电视剧", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226485/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv8hd.m3u8",
            "备用2" to "http://bztv.tvbus.cc:8081/cdnlive/cctv8.m3u8",
        ),
        channel(
            "builtin:cctv-9", "CCTV-9 纪录", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226944/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv9hd.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv9hd",
        ),
        channel(
            "builtin:cctv-10", "CCTV-10 科教", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226937/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv10hd.m3u8",
            "备用2" to "http://38.75.136.137:98/gslb/dsdqbv/cctv10hd.m3u8?auth=test20251009",
        ),
        channel(
            "builtin:cctv-11", "CCTV-11 戏曲", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226334/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv11hd.m3u8",
            "备用2" to "http://198.204.228.26/live/cctv11hd.m3u8",
        ),
        channel(
            "builtin:cctv-12", "CCTV-12 社会与法", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226942/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv12hd.m3u8",
            "备用2" to "http://107.150.60.122/live/cctv12hd.m3u8",
        ),
        channel(
            "builtin:cctv-13", "CCTV-13 新闻", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226316/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv13hd.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv13hd",
        ),
        channel(
            "builtin:cctv-14", "CCTV-14 少儿", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226947/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv14hd.m3u8",
            "备用2" to "http://198.204.228.26/live/cctv14hd.m3u8",
        ),
        channel(
            "builtin:cctv-15", "CCTV-15 音乐", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226333/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv15hd.m3u8",
            "备用2" to "http://204.12.221.218:8181/3m1080p/cctv15.m3u8",
        ),
        channel(
            "builtin:cctv-16", "CCTV-16 奥林匹克", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221227002/1.m3u8",
            "备用1" to "http://207.56.13.146:81/cdnlive/cctv16.m3u8",
            "备用2" to "http://74.91.26.218:82/live/cctv16hd.m3u8",
        ),
        channel(
            "builtin:cctv-17", "CCTV-17 农业农村", ChannelCategory.CCTV,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226318/1.m3u8",
            "备用1" to "http://74.91.26.218:82/live/cctv17hd.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv17hd",
        ),
        channel(
            "builtin:cgtn", "CGTN", ChannelCategory.CCTV,
            "主源" to "https://0472.org/hls/cgtn.m3u8",
            "备用1" to "https://amg00405-rakutentv-cgtn-rakuten-i9tar.amagi.tv/master.m3u8",
        ),
        channel(
            "builtin:cgtn-doc", "CGTN 纪录", ChannelCategory.CCTV,
            "主源" to "https://0472.org/hls/cgtnd.m3u8",
        ),
        channel(
            "builtin:cgtn-es", "CGTN 西语", ChannelCategory.CCTV,
            "主源" to "https://0472.org/hls/cgtnx.m3u8",
            "备用1" to "https://espanol-livews.cgtn.com/hls/LSveOGBaBw41Ea7ukkVAUdKQ220802LSTexu6xAuFH8VZNBLE1ZNEa220802cd/playlist.m3u8",
        ),
        channel(
            "builtin:cgtn-fr", "CGTN 法语", ChannelCategory.CCTV,
            "主源" to "https://0472.org/hls/cgtnf.m3u8",
            "备用1" to "https://amg01314-cgtn-amg01314c2-rakuten-us-1319.playouts.now.amagi.tv/cgtn-fr-rakuten/playlist.m3u8",
        ),
        channel(
            "builtin:cgtn-ar", "CGTN 阿语", ChannelCategory.CCTV,
            "主源" to "https://0472.org/hls/cgtna.m3u8",
            "备用1" to "https://dash3.antik.sk/live/test_cgtn_arb_tizen/playlist.m3u8",
        ),
        channel(
            "builtin:cgtn-ru", "CGTN 俄语", ChannelCategory.CCTV,
            "主源" to "https://0472.org/hls/cgtne.m3u8",
            "备用1" to "https://russian-livews.cgtn.com/hls/LSvexABhNipibK5KRuUkvHZ7220802LSTeze9o8tdFXMHsb1VosgoT220802cd/playlist.m3u8",
        ),

        // ==================== 卫视 ====================
        channel(
            "builtin:hunan-tv", "湖南卫视", ChannelCategory.SATELLITE,
            "主源" to "http://107.150.60.122/live/hnwshd.m3u8",
            "备用1" to "http://74.91.26.218:82/live/hnwshd.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=hnwshd",
        ),
        channel(
            "builtin:zhejiang-tv", "浙江卫视", ChannelCategory.SATELLITE,
            "主源" to "https://ali-m-l.cztv.com/channels/lantian/channel001/1080p.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=zjwshd",
            "备用2" to "http://107.150.60.122/live/zjwshd.m3u8",
        ),
        channel(
            "builtin:dongfang-tv", "东方卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=dfwshd",
            "备用1" to "http://38.75.136.137:98/gslb/dsdqpub/dfwshd.m3u8?auth=testpub",
            "备用2" to "http://107.150.60.122/live/dfwshd.m3u8",
        ),
        channel(
            "builtin:jiangsu-tv", "江苏卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=jswshd",
            "备用1" to "http://107.150.60.122/live/jswshd.m3u8",
        ),
        channel(
            "builtin:beijing-tv", "北京卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=bjwshd",
            "备用1" to "http://107.150.60.122/live/bjwshd.m3u8",
            "备用2" to "http://go.bkpcp.top/mg/bjws",
        ),
        channel(
            "builtin:shenzhen-tv", "深圳卫视", ChannelCategory.SATELLITE,
            "主源" to "https://livepull-tcms.sztv.com.cn/live/sz4Kpgm.m3u8",
            "备用1" to "http://107.150.60.122/live/szwshd.m3u8",
            "备用2" to "http://iptv.huuc.edu.cn/hls/szhd.m3u8",
        ),
        channel(
            "builtin:guangdong-tv", "广东卫视", ChannelCategory.SATELLITE,
            "主源" to "https://h5cul1yar48um3t.wcetv.com/hls/gdsatellite.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=gdwshd",
            "备用2" to "http://107.150.60.122/live/gdwshd.m3u8",
        ),
        channel(
            "builtin:shandong-tv", "山东卫视", ChannelCategory.SATELLITE,
            "主源" to "http://125.210.152.18:9090/live/SDWSHD_H265.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=sdwshd",
            "备用2" to "http://107.150.60.122/live/sdwshd.m3u8",
        ),
        channel(
            "builtin:tianjin-tv", "天津卫视", ChannelCategory.SATELLITE,
            "主源" to "http://[2409:8087:1a01:df::4030]:80/TVOD/88888888/224/3221225972/main.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=tjwshd",
            "备用2" to "http://iptv.huuc.edu.cn/hls/tjhd.m3u8",
        ),
        channel(
            "builtin:anhui-tv", "安徽卫视", ChannelCategory.SATELLITE,
            "主源" to "http://107.150.60.122/live/ahwshd.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=ahwshd",
            "备用2" to "https://restream.pdtvhd.com/memfs/c1cd2ed0-3b2b-4c25-af98-a7fb0b1eb130.m3u8",
        ),
        channel(
            "builtin:jiangxi-tv", "江西卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=jxwshd",
            "备用1" to "http://107.150.60.122/live/jxwshd.m3u8",
        ),
        channel(
            "builtin:hubei-tv", "湖北卫视", ChannelCategory.SATELLITE,
            "主源" to "http://107.150.60.122/live/hbwshd.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=hbwshd",
            "备用2" to "http://iptv.huuc.edu.cn/hls/hbhd.m3u8",
        ),
        channel(
            "builtin:henan-tv", "河南卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=hawshd",
            "备用1" to "http://38.75.136.137:98/gslb/dsdqpub/hawshd.m3u8?auth=testpub",
            "备用2" to "http://107.150.60.122/live/hawshd.m3u8",
        ),
        channel(
            "builtin:sichuan-tv", "四川卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=scwshd",
            "备用1" to "http://107.150.60.122/live/scwshd.m3u8",
            "备用2" to "http://39.134.115.163:8080/PLTV/88888910/224/3221225733/index.m3u8",
        ),
        channel(
            "builtin:heilongjiang-tv", "黑龙江卫视", ChannelCategory.SATELLITE,
            "主源" to "http://107.150.60.122/live/hljwshd.m3u8",
            "备用1" to "http://223.110.245.161/ott.js.chinamobile.com/PLTV/3/224/3221227492/index.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=hljwshd",
        ),
        channel(
            "builtin:liaoning-tv", "辽宁卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=lnwshd",
            "备用1" to "http://39.134.39.37/PLTV/88888888/224/3221226209/index.m3u8",
            "备用2" to "http://107.150.60.122/live/lnwshd.m3u8",
        ),
        channel(
            "builtin:jilin-tv", "吉林卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=jlwshd",
            "备用1" to "http://107.150.60.122/live/jlwshd.m3u8",
        ),
        channel(
            "builtin:hebei-tv", "河北卫视", ChannelCategory.SATELLITE,
            "主源" to "https://event.pull.hebtv.com/live/live101.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=hewshd",
        ),
        channel(
            "builtin:shanxi-tv", "山西卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=sxwshd",
            "备用1" to "http://107.150.60.122/live/sxwshd.m3u8",
        ),
        channel(
            "builtin:guizhou-tv", "贵州卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=gzwshd",
            "备用1" to "http://183.207.248.71/gitv/live1/G_GUIZHOU/G_GUIZHOU",
            "备用2" to "http://107.150.60.122/live/gzwshd.m3u8",
        ),
        channel(
            "builtin:yunnan-tv", "云南卫视", ChannelCategory.SATELLITE,
            "主源" to "https://hwapi.yunshicloud.com/8xughf/e0bx15.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=ynwshd",
            "备用2" to "http://107.150.60.122/live/ynwshd.m3u8",
        ),
        channel(
            "builtin:guangxi-tv", "广西卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=gxwshd",
            "备用1" to "http://107.150.60.122/live/gxwshd.m3u8",
        ),
        channel(
            "builtin:dongnan-tv", "东南卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=dnwshd",
            "备用1" to "http://107.150.60.122/live/dnwshd.m3u8",
            "备用2" to "http://iptv.huuc.edu.cn/hls/dnhd.m3u8",
        ),
        channel(
            "builtin:hainan-tv", "海南卫视", ChannelCategory.SATELLITE,
            "主源" to "http://120.76.248.139/live/bfgd/4200000473.m3u8",
            "备用1" to "https://zby.130519.xyz/PLTV/11/224/3221226154/index.m3u8",
        ),
        channel(
            "builtin:gansu-tv", "甘肃卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=gswshd",
            "备用1" to "http://38.75.136.137:98/gslb/dsdqpub/gswshd.m3u8?auth=testpub",
        ),
        channel(
            "builtin:ningxia-tv", "宁夏卫视", ChannelCategory.SATELLITE,
            "主源" to "http://107.150.60.122/live/nxws.m3u8",
            "备用1" to "http://39.134.115.163:8080/PLTV/88888910/224/3221225726/index.m3u8",
        ),
        channel(
            "builtin:qinghai-tv", "青海卫视", ChannelCategory.SATELLITE,
            "主源" to "http://107.150.60.122/live/qhws.m3u8",
            "备用1" to "https://hls-qhmh.lanzhousobey.cn/qhmh/mhds.m3u8",
            "备用2" to "http://39.134.115.163:8080/PLTV/88888910/224/3221225727/index.m3u8",
        ),
        channel(
            "builtin:xinjiang-tv", "新疆卫视", ChannelCategory.SATELLITE,
            "主源" to "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/PLTV/88888888/224/3221226546/1.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=xjws",
            "备用2" to "http://107.150.60.122/live/xjws.m3u8",
        ),
        channel(
            "builtin:xizang-tv", "西藏卫视", ChannelCategory.SATELLITE,
            "主源" to "http://107.150.60.122/live/xzws.m3u8",
            "备用1" to "https://pili-live-hls-zdzz-tv.zzxwcm.cn/zdzz-tv/nqzh.m3u8",
            "备用2" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=xzws",
        ),
        channel(
            "builtin:neimenggu-tv", "内蒙古卫视", ChannelCategory.SATELLITE,
            "主源" to "http://play1-qk.nmtv.cn/live/1769652018126032.m3u8",
            "备用1" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=nmgws",
            "备用2" to "http://107.150.60.122/live/nmgws.m3u8",
        ),
        channel(
            "builtin:chongqing-tv", "重庆卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cqwshd",
            "备用1" to "http://107.150.60.122/live/cqwshd.m3u8",
            "备用2" to "http://iptv.huuc.edu.cn/hls/cqhd.m3u8",
        ),
        channel(
            "builtin:shaanxi-tv", "陕西卫视", ChannelCategory.SATELLITE,
            "主源" to "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=snwshd",
            "备用1" to "http://107.150.60.122/live/snwshd.m3u8",
        ),

        // ==================== 外国频道 ====================
        channel(
            "builtin:bbc-world-news", "BBC World News", ChannelCategory.FOREIGN,
            "主源" to "http://46.32.176.50/bbcworld/index.m3u8",
            "备用1" to "https://mumbai-edge.smartplaytv.in/BBCNews/index.m3u8",
        ),
        channel(
            "builtin:cnn-international", "CNN International", ChannelCategory.FOREIGN,
            "主源" to "https://d3bp6dwmpbdajl.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-ury0meh5m4nzm/index.m3u8",
            "备用1" to "https://d2sybdl23b9psq.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-bocx3i7bjz0dg/CNNFAST_GB.m3u8",
        ),
        channel(
            "builtin:nhk-world-japan", "NHK World-Japan", ChannelCategory.FOREIGN,
            "主源" to "https://cdn.pishow.tv/ott/live/455/master.m3u8",
            "备用1" to "https://freem3u.xyz/api/live/play.m3u8?vid=106",
        ),
        channel(
            "builtin:dw-english", "DW English", ChannelCategory.FOREIGN,
            "主源" to "https://amg01644-amg01644c1-amgplt0343.playout.now3.amagi.tv/ts-eu-w1-n2/playlist/amg01644-amg01644c1-amgplt0343/playlist.m3u8",
        ),
        channel(
            "builtin:france24-english", "France 24 English", ChannelCategory.FOREIGN,
            "主源" to "https://freem3u.xyz/api/live/play.m3u8?vid=96",
            "备用1" to "http://nmk.ioapk.com:5050/4gtv-live146/index.m3u8",
        ),
        channel(
            "builtin:aljazeera-english", "Al Jazeera English", ChannelCategory.FOREIGN,
            "主源" to "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8",
            "备用1" to "http://stream.mcquack.net/131/index.m3u8",
        ),
        channel(
            "builtin:cna", "CNA", ChannelCategory.FOREIGN,
            "主源" to "https://freem3u.xyz/api/live/play.m3u8?vid=112",
            "备用1" to "https://d2e1asnsl7br7b.cloudfront.net/7782e205e72f43aeb4a48ec97f66ebbe/index.m3u8",
        ),
        channel(
            "builtin:kbs-world", "KBS World", ChannelCategory.FOREIGN,
            "主源" to "https://stream8.cinerama.uz/1448/playlist.m3u8",
            "备用1" to "http://31.148.48.15/KBS_World/index.m3u8",
        ),
        channel(
            "builtin:abc-news-au", "ABC News (Australia)", ChannelCategory.FOREIGN,
            "主源" to "https://abc-news-dmd-streams-1.akamaized.net/out/v1/701126012d044971b3fa89406a440133/index.m3u8",
        ),
        channel(
            "builtin:trt-world", "TRT World", ChannelCategory.FOREIGN,
            "主源" to "https://mumbai-edge.smartplaytv.in/TRTWorld/index.m3u8",
            "备用1" to "https://dash2.antik.sk/live/test_trt_world_atktv/playlist.m3u8",
        ),
    )

    /** 内置频道按 id 的索引，供单频道查询。 */
    val byId: Map<String, Channel> = all.associateBy { it.id }

    /**
     * 组装一个内置频道。
     *
     * @param id 频道唯一标识（`builtin:` 前缀）
     * @param name 频道展示名称（频道官方命名，属数据不参与 i18n）
     * @param category 所属分类
     * @param sources 信号源的「标签 to 地址」对，按顺序作为切换线路
     */
    private fun channel(
        id: String,
        name: String,
        category: ChannelCategory,
        vararg sources: Pair<String, String>,
    ): Channel = Channel(
        id = id,
        name = name,
        category = category,
        sources = sources.map { (label, url) -> ChannelSource(label = label, url = url) },
    )
}
