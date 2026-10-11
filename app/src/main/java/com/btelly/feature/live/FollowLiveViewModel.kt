package com.btelly.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btelly.model.live.LiveRoomItem
import com.btelly.repository.LiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「我的关注」直播页：分页拉取全部已关注主播，按开播状态分组。
 * live_status != 0 视为可播（直播中/轮播中），== 0 视为未开播。
 */
class FollowLiveViewModel(
    private val liveRepository: LiveRepository
) : ViewModel() {

    data class State(
        val liveRooms: List<LiveRoomItem> = emptyList(),
        val offlineRooms: List<LiveRoomItem> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val endReached: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var nextPage = 1

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            liveRepository.getLiveFollowList(page = 1).fold(
                onSuccess = { page ->
                    nextPage = 2
                    _state.value = State(
                        liveRooms = page.rooms.filter { it.liveStatus != 0 },
                        offlineRooms = page.rooms.filter { it.liveStatus == 0 },
                        endReached = !page.hasMore
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
                }
            )
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.endReached) return
        viewModelScope.launch {
            _state.value = current.copy(loadingMore = true)
            liveRepository.getLiveFollowList(page = nextPage).fold(
                onSuccess = { page ->
                    nextPage += 1
                    _state.value = _state.value.copy(
                        liveRooms = _state.value.liveRooms + page.rooms.filter { it.liveStatus != 0 },
                        offlineRooms = _state.value.offlineRooms + page.rooms.filter { it.liveStatus == 0 },
                        loadingMore = false,
                        endReached = !page.hasMore
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(loadingMore = false, error = e.message)
                }
            )
        }
    }
}
