package com.btelly.ui.dialog

import android.content.Context
import android.graphics.Color
import android.os.CountDownTimer
import android.text.Html
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
import com.btelly.R
import com.btelly.databinding.DialogUsageTipBinding

class UsageTipDialog(context: Context) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogUsageTipBinding.inflate(LayoutInflater.from(context))
    private var countDownTimer: CountDownTimer? = null

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.6).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        binding.buttonConfirm.setTextColor(Color.GRAY)
        binding.buttonConfirm.isClickable = false
        binding.buttonConfirm.text = context.getString(R.string.confirm_countdown, 10)

        binding.buttonConfirm.setOnClickListener { dismiss() }

        binding.textContent.text =
            Html.fromHtml(context.getString(R.string.usage_tip_content), Html.FROM_HTML_MODE_COMPACT)

        setOnShowListener {
            startCountdown()
        }
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                binding.buttonConfirm.text =
                    context.getString(R.string.confirm_countdown, seconds.toInt())
            }

            override fun onFinish() {
                binding.buttonConfirm.setText(R.string.confirm)
                binding.buttonConfirm.setTextColor(
                    context.resources.getColor(R.color.pink, null)
                )
                binding.buttonConfirm.isClickable = true
                binding.buttonConfirm.requestFocus()
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        countDownTimer?.cancel()
    }
}
