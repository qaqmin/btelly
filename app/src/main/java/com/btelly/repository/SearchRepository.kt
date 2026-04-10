package com.btelly.repository

import com.btelly.model.search.HotWordModel
import com.btelly.model.search.SearchType
import com.btelly.model.search.SearchVideoOrder
import com.btelly.repository.remote.SearchRepository as NetworkSearchRepository

class SearchRepository(
    private val delegate: NetworkSearchRepository = NetworkSearchRepository()
) {
    suspend fun loadHotSearchWords(): Result<List<HotWordModel>> = delegate.loadHotSearchWords()

    suspend fun searchSuggest(keyword: String): Result<List<HotWordModel>> = delegate.searchSuggest(keyword)

    suspend fun searchAll(keyword: String) = delegate.searchAll(keyword)

    suspend fun searchByType(
        searchType: SearchType,
        keyword: String,
        page: Int,
        pageSize: Int,
        order: SearchVideoOrder = SearchVideoOrder.TotalRank
    ) = delegate.searchByType(searchType, keyword, page, pageSize, order)

    suspend fun search(
        keyword: String,
        page: Int,
        pageSize: Int,
        order: SearchVideoOrder = SearchVideoOrder.TotalRank
    ) = delegate.search(keyword, page, pageSize, order)
}
