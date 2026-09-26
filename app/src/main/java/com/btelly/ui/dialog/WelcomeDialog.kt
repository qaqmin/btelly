// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.ui.dialog

import com.btelly.core.ui.motion.applyMinWindowAnim
import com.btelly.core.ui.base.UiCarSpacing
import android.content.Context
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
import com.btelly.R
import com.btelly.core.common.BrandGene
import com.btelly.databinding.DialogWelcomeBinding

/**
 * 首次启动欢迎弹窗（P0-1）。
 *
 * 只承担两件事：免责声明 + 项目开源仓库地址；署名走 [BrandGene] 单一事实来源。
 * 已移除旧「使用提示」的红字警告、秒倒计时与公开秘籍文档。
 */
class WelcomeDialog(context: Context) : AppCompatDialog(context, R.style.DialogTheme) {

    init {
        // Min化：对话框弹簧呈现（电视模式零动画）
        applyMinWindowAnim()
    }

    private val binding = DialogWelcomeBinding.inflate(LayoutInflater.from(context))

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        UiCarSpacing.apply(binding.root) // 车机模式间距铺开（电视模式零改动）
        setCancelable(true)
        setCanceledOnTouchOutside(false)
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.62).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        binding.textRepo.text = context.getString(R.string.welcome_repo, BrandGene.REPO_URL)
        binding.textCredit.text = context.getString(
            R.string.welcome_credit,
            BrandGene.AUTHOR_NAME,
            BrandGene.AUTHOR_ID
        )
        binding.buttonConfirm.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        binding.buttonConfirm.requestFocus()
    }
}
