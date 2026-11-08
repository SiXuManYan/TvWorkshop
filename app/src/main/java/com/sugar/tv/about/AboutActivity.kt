package com.sugar.tv.about

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sugar.tv.BuildConfig
import com.sugar.tv.R
import com.sugar.tv.base.BaseActivity
import com.sugar.tv.databinding.ActivityAboutBinding

/** 关于页：展示当前构建版本、三份离线文档入口，并保留原有频道来源说明。 */
class AboutActivity : BaseActivity() {

    /** Binding 由当前页面持有，实例化阶段不访问尚未挂载的 Context。 */
    private lateinit var binding: ActivityAboutBinding

    /** 来源说明窗口仅由页面持有，销毁时主动关闭。 */
    private var sourceDialog: AlertDialog? = null

    /** 初始化原生信息卡与显式导航，版本直接取自当前构建配置。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyDocumentInsets()
        binding.aboutToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.aboutVersion.text = getString(
            R.string.about_page_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.aboutHistory.setOnClickListener { openDocument(document = LocalDocument.VERSION_HISTORY) }
        binding.aboutAgreement.setOnClickListener { openDocument(document = LocalDocument.USER_AGREEMENT) }
        binding.aboutPrivacy.setOnClickListener { openDocument(document = LocalDocument.PRIVACY_POLICY) }
        binding.aboutSources.setOnClickListener { showSourceInformation() }
    }

    /** 三个入口共用同一阅读页，仅传递白名单标识。 */
    private fun openDocument(document: LocalDocument) {
        startActivity(LocalDocumentActivity.createIntent(context = this, document = document))
    }

    /** 来源与版权简述继续复用既有文案，避免新增文档覆盖原有披露。 */
    private fun showSourceInformation() {
        if (sourceDialog?.isShowing == true) return
        sourceDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.action_ok, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { sourceDialog = null }
                dialog.show()
            }
    }

    /** 主动解除页面窗口引用。 */
    override fun onDestroy() {
        sourceDialog?.dismiss()
        sourceDialog = null
        super.onDestroy()
    }
}
