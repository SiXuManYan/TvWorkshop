package com.sugar.tv.base

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * 所有 Activity 的基类：统一在进入页面前开启 edge-to-edge，
 * 让内容延伸到系统栏后方；各页面通过 Insets 自行避让操作区。
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 与 setContentView 之前调用。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }
}
