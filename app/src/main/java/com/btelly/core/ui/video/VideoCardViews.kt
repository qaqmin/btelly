package com.btelly.core.ui.video

import android.view.View
import android.widget.ImageView

data class VideoCardViews(
    val root: View,
    val imageView: ImageView,
    val progressBar: VideoCardProgressView,
    val coverMetaOverlay: VideoCoverMetaOverlayView,
    val textLayer: VideoCardTextLayerView
)
