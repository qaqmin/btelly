package com.btelly.feature.home

import com.btelly.model.video.VideoModel
import kotlinx.coroutines.flow.StateFlow

interface VideoFeedViewModel {
    val uiState: StateFlow<FeedUiState<VideoModel>>

    fun loadInitial()

    fun refresh()

    fun loadMore()

    fun consumeListChange()

    fun consumeFollowStatusUpdate() {}
}
