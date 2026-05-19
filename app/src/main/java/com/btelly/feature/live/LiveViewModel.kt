package com.btelly.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btelly.core.common.log.AppLog
import com.btelly.model.live.LiveAreaCategoryParent
import com.btelly.repository.LiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveViewModel(
    private val liveRepository: LiveRepository
) : ViewModel() {

    private var lastLoadedAt = 0L

    private val _categories = MutableStateFlow<List<LiveAreaCategoryParent>>(emptyList())
    val categories: StateFlow<List<LiveAreaCategoryParent>> = _categories.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadLiveAreas() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            val t0 = System.currentTimeMillis()
            AppLog.d("LivePerf", "loadLiveAreas: 开始请求分区列表")

            val result = liveRepository.getLiveAreas()

            AppLog.d("LivePerf", "loadLiveAreas: 分区列表返回, 耗时=${System.currentTimeMillis() - t0}ms")

            result.fold(
                onSuccess = { areaList ->
                    AppLog.d("LivePerf", "loadLiveAreas: 分区数量=${areaList.size}")
                    val recommendCategory = LiveAreaCategoryParent(
                        id = 0,
                        name = "推荐"
                    )
                    _categories.value = listOf(recommendCategory) + areaList
                    lastLoadedAt = System.currentTimeMillis()
                },
                onFailure = { exception ->
                    AppLog.e("LivePerf", "loadLiveAreas: 失败, ${exception.message}")
                    _error.value = exception.message
                }
            )

            _loading.value = false
        }
    }

    fun shouldRefresh(ttlMs: Long): Boolean {
        if (_categories.value.isEmpty()) {
            return true
        }
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }
}
