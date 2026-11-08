package com.sugar.tv.data

import java.net.URI
import java.net.URISyntaxException

/** 解析带主机名与有效端口的 HTTP(S) 地址；缺少主机的 `http:xxx` 等输入返回 `null`。 */
internal fun parseHttpUri(url: String): URI? = try {
    URI(url).takeIf { uri ->
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 0..65535)
    }
} catch (_: URISyntaxException) {
    null
}
