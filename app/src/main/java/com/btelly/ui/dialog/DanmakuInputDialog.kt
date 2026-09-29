package com.btelly.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import com.btelly.R
import com.btelly.core.common.log.AppLog
import com.btelly.core.ui.base.UiCarSpacing
import com.btelly.core.ui.motion.applyMinWindowAnim
import com.btelly.databinding.DialogDanmakuInputBinding
import com.btelly.feature.search.view.KeyboardView
import com.btelly.repository.comment.DanmakuPostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 发弹幕对话框。
 *
 * 关键设计决定——**不用系统 EditText**：遥控器上 EditText 一获焦就弹系统输入法，
 * 把弹幕输入整个流程打断（输入法自己抢焦点、遮住半屏、BACK 行为不可控）。
 * 这里用一个只读 TextView 显示已输入内容，实际输入交给 [KeyboardView]
 * （搜索页那套 T9/全键盘，已经过真机验证）。
 *
 * 发送节流由 [DanmakuPostRepository] 负责（进程级最短间隔），本 Dialog 只在 UI 上
 * 把剩余冷却时间显示出来，不自己记状态。
 */
class DanmakuInputDialog(
    context: Context,
    private val aid: Long,
    private val onSent: (() -> Unit)? = null
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    companion object {
        private const val TAG = "DanmakuInputDialog"

        /** 弹幕模式（与 B 站接口的 mode 字段对应） */
        private const val MODE_ROLL = 1
        private const val MODE_TOP = 5
        private const val MODE_BOTTOM = 4
    }

    private val binding = DialogDanmakuInputBinding.inflate(LayoutInflater.from(context))
    private val danmakuPostRepository: DanmakuPostRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val text = StringBuilder()
    private var mode = MODE_ROLL
    private var modeCycle = listOf(
        MODE_ROLL to R.string.danmaku_mode_roll,
        MODE_TOP to R.string.danmaku_mode_top,
        MODE_BOTTOM to R.string.danmaku_mode_bottom
    )
    private var modeIndex = 0
    private var sending = false

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        applyMinWindowAnim()
        UiCarSpacing.apply(binding.root)
        setCanceledOnTouchOutside(true)

        binding.viewKeyboard.setKeySelectListener(object : KeyboardView.KeySelectListener {
            override fun onInsertText(text: String) {
                appendText(text)
            }

            override fun onDelete() {
                if (text.isNotEmpty()) {
                    text.deleteCharAt(text.length - 1)
                    refreshInput()
                }
            }

            override fun onClear() {
                text.clear()
                refreshInput()
            }

            override fun onSearch() {
                // 键盘的"搜索"键在弹幕场景语义就是"确认发送"
                send()
            }
        })

        binding.buttonMode.setOnClickListener {
            modeIndex = (modeIndex + 1) % modeCycle.size
            mode = modeCycle[modeIndex].first
            binding.buttonMode.setText(modeCycle[modeIndex].second)
        }

        binding.buttonCancel.setOnClickListener { dismiss() }

        binding.buttonSend.setOnClickListener { send() }

        setOnShowListener {
            binding.root.post {
                binding.viewKeyboard.requestPrimaryFocus()
            }
        }

        refreshInput()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    private fun appendText(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        if (text.length + trimmed.length > DanmakuPostRepository.MAX_DANMAKU_LENGTH) return
        text.append(trimmed)
        refreshInput()
    }

    private fun refreshInput() {
        binding.textInput.text = text.toString()
        binding.textCount.text = context.getString(
            R.string.danmaku_char_count,
            text.length,
            DanmakuPostRepository.MAX_DANMAKU_LENGTH
        )
    }

    private fun send() {
        if (sending) return
        val content = text.toString().trim()
        if (content.isEmpty()) {
            Toast.makeText(context, R.string.danmaku_input_hint, Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        binding.buttonSend.isEnabled = false
        scope.launch {
            val result = runCatching {
                danmakuPostRepository.postDanmaku(aid = aid, text = content, mode = mode)
            }.getOrElse { e ->
                AppLog.w(TAG, "postDanmaku threw: ${e.message}")
                return@launch finishSend(success = false, message = context.getString(R.string.danmaku_send_failed))
            }
            result.onSuccess { response ->
                finishSend(
                    success = response.isSuccess,
                    message = response.errorMessage.ifEmpty {
                        context.getString(
                            if (response.isSuccess) R.string.danmaku_sent else R.string.danmaku_send_failed
                        )
                    }
                )
            }.onFailure { e ->
                finishSend(
                    success = false,
                    message = e.message ?: context.getString(R.string.danmaku_send_failed)
                )
            }
        }
    }

    private fun finishSend(success: Boolean, message: String) {
        sending = false
        binding.buttonSend.isEnabled = true
        if (success) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onSent?.invoke()
            dismiss()
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
