package com.btelly.ui.dialog

import com.btelly.core.ui.base.UiCarSpacing
import android.content.Context
import android.view.LayoutInflater
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import com.btelly.R
import com.btelly.databinding.DialogVideoInfoBinding
import com.btelly.core.ui.image.ImageLoader

class VideoInfoDialog(
    context: Context,
    private val coverUrl: String,
    private val title: String,
    private val description: String
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogVideoInfoBinding.inflate(LayoutInflater.from(context))

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        UiCarSpacing.apply(binding.root) // 车机模式间距铺开（电视模式零改动）
        setCanceledOnTouchOutside(true)
        bindContent()
        binding.buttonClose.setOnClickListener { dismiss() }
    }

    private fun bindContent() {
        ImageLoader.loadVideoCover(
            imageView = binding.imageView,
            url = coverUrl,
            placeholder = R.drawable.default_video,
            error = R.drawable.default_video
        )
        binding.textTitle.text = title
        binding.textDescription.text = description
    }
}
