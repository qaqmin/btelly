package com.btelly.ui.fragment.main.me

import com.btelly.model.video.HistoryVideoModel
import com.btelly.model.video.VideoModel

data class MeListUiState(
    val historyVideos: List<HistoryVideoModel> = emptyList(),
    val laterVideos: List<VideoModel> = emptyList()
)
