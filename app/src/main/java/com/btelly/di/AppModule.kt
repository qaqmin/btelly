package com.btelly.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.btelly.event.AppEventHub
import com.btelly.network.NetworkManager
import com.btelly.network.api.ApiService
import com.btelly.repository.AllSeriesRepository
import com.btelly.repository.AuthRepository
import com.btelly.repository.FavoriteRepository
import com.btelly.repository.HomeLaneRepository
import com.btelly.repository.LiveRepository
import com.btelly.repository.SearchRepository
import com.btelly.repository.SeriesRepository
import com.btelly.repository.UserRepository
import com.btelly.repository.VideoRepository
import com.btelly.ui.fragment.main.category.CategoryViewModel
import com.btelly.ui.fragment.main.dynamic.DynamicViewModel
import com.btelly.ui.fragment.main.home.HotViewModel
import com.btelly.ui.fragment.main.home.RecommendViewModel
import com.btelly.ui.fragment.main.live.LiveListViewModel
import com.btelly.ui.fragment.main.live.LiveRecommendViewModel
import com.btelly.ui.fragment.main.live.LiveViewModel
import com.btelly.ui.fragment.main.me.MeListViewModel
import com.btelly.ui.fragment.main.me.MeViewModel
import com.btelly.ui.fragment.main.search.SearchViewModel
import com.btelly.ui.fragment.player.LivePlayerViewModel
import com.btelly.ui.fragment.player.VideoPlayerViewModel
import com.btelly.ui.fragment.series.SeriesDetailViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

@OptIn(UnstableApi::class)
val networkModule = module {
    single<ApiService> { NetworkManager.apiService }
}

@OptIn(UnstableApi::class)
val repositoryModule = module {
    single { com.btelly.repository.remote.AllSeriesRepository() }
    single { com.btelly.repository.remote.AuthRepository(get()) }
    single { com.btelly.repository.remote.FavoriteRepository(get()) }
    single { com.btelly.repository.remote.HomeLaneRepository(get(), get(), get()) }
    single { com.btelly.repository.remote.LiveRepository() }
    single { com.btelly.repository.remote.SearchRepository() }
    single { com.btelly.repository.remote.SeriesRepository() }
    single { com.btelly.repository.remote.VideoRepository(get()) }
    single { AllSeriesRepository(get()) }
    single { AuthRepository(get()) }
    single { FavoriteRepository(get()) }
    single { HomeLaneRepository(get()) }
    single { LiveRepository(get()) }
    single { SearchRepository(get()) }
    single { SeriesRepository(get()) }
    single { VideoRepository(get()) }
    single { UserRepository() }
}

@OptIn(UnstableApi::class)
val viewModelModule = module {
    viewModel { RecommendViewModel(get()) }
    viewModel { HotViewModel(get()) }
    viewModel { VideoPlayerViewModel(get(), androidContext()) }
    viewModel { CategoryViewModel(get()) }
    viewModel { DynamicViewModel(get()) }
    viewModel { LiveViewModel(get()) }
    viewModel { LiveListViewModel(get()) }
    viewModel { LiveRecommendViewModel(get()) }
    viewModel { MeListViewModel(get()) }
    viewModel { MeViewModel(get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { LivePlayerViewModel(get()) }
    viewModel { SeriesDetailViewModel(get()) }
}

val eventModule = module {
    single { AppEventHub() }
}

val appModules = listOf(
    networkModule,
    repositoryModule,
    eventModule,
    viewModelModule
)
