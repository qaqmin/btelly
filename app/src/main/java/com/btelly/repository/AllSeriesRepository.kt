package com.btelly.repository

import com.btelly.model.series.AllSeriesFilterModel
import com.btelly.repository.remote.AllSeriesRepository as NetworkAllSeriesRepository

typealias AllSeriesPage = com.btelly.repository.remote.AllSeriesPage

class AllSeriesRepository(
    private val delegate: NetworkAllSeriesRepository
) {
    suspend fun getAllSeries(
        type: Int,
        page: Int,
        filters: List<AllSeriesFilterModel> = emptyList()
    ) = delegate.getAllSeries(type, page, filters)
}
