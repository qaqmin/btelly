package com.btelly.core.ui.video

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

data class VideoCardViews(
    val root: View,
    val imageView: ImageView,
    val progressBar: ProgressBar,
    val coverMetaOverlay: VideoCoverMetaOverlayView,
    val iconPlaying: ImageView,
    val textView: FastTitleTextView,
    val textOverflow: TextView,
    val ownerRow: VideoOwnerRowView,
    val iconHistoryDevice: ImageView?,
    val textHistoryViewTime: TextView?
)
