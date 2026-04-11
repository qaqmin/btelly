package com.btelly.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.btelly.event.AppEventHub
import com.btelly.network.NetworkManager
import com.btelly.network.api.ApiService
import com.btelly.network.security.NetworkManagerSecurityGateway
import com.btelly.network.security.NetworkManagerWebGateway
import com.btelly.network.security.NetworkSecurityGateway
import com.btelly.network.security.NetworkWebGateway
import com.btelly.network.session.NetworkManagerSessionGateway
import com.btelly.network.session.NetworkSessionGateway
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
import com.btelly.feature.home.HotViewModel
import com.btelly.feature.home.RecommendViewModel
import com.btelly.ui.fragment.main.live.LiveListViewModel
import com.btelly.ui.fragment.main.live.LiveRecommendViewModel
import com.btelly.ui.fragment.main.live.LiveViewModel
import com.btelly.ui.fragment.main.me.MeListViewModel
import com.btelly.ui.fragment.main.me.MeViewModel
import com.btelly.ui.fragment.main.search.SearchViewModel
import com.btelly.feature.player.LivePlayerViewModel
import com.btelly.feature.player.VideoPlayerViewModel
import com.btelly.ui.fragment.series.SeriesDetailViewModel
import com.btelly.utils.CookieManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

@OptIn(UnstableApi::class)
val networkModule = module {
    single<ApiService> { NetworkManager.apiService }
    single<OkHttpClient> { NetworkManager.getOkHttpClient() }
    single<CookieManager> { NetworkManager.getCookieManager() }
    single<NetworkSessionGateway> { NetworkManagerSessionGateway() }
    single<NetworkSecurityGateway> { NetworkManagerSecurityGateway() }
    single<NetworkWebGateway> { NetworkManagerWebGateway() }
}

@OptIn(UnstableApi::class)
val repositoryModule = module {
    single { com.btelly.repository.remote.AllSeriesRepository(get()) }
    single { com.btelly.repository.remote.AuthRepository(get(), get()) }
    single { com.btelly.repository.remote.FavoriteRepository(get(), get()) }
    single { com.btelly.repository.remote.HomeLaneRepository(get(), get(), get(), get()) }
    single { com.btelly.repository.remote.LiveRepository(get(), get()) }
    single { com.btelly.repository.remote.SearchRepository(get(), get()) }
    single { com.btelly.repository.remote.SeriesRepository(get(), get()) }
    single { com.btelly.repository.remote.UserRepository(get(), get()) }
    single { com.btelly.repository.remote.VideoRepository(get(), get(), get(), get()) }
    single { AllSeriesRepository(get()) }
    single { AuthRepository(get()) }
    single { FavoriteRepository(get()) }
    single { HomeLaneRepository(get()) }
    single { LiveRepository(get()) }
    single { SearchRepository(get()) }
    single { SeriesRepository(get()) }
    single { VideoRepository(get(), get()) }
    single { UserRepository(get(), get()) }
}

@OptIn(UnstableApi::class)
val viewModelModule = module {
    viewModel { RecommendViewModel(get()) }
    viewModel { HotViewModel(get()) }
    viewModel { VideoPlayerViewModel(get(), get(), get(), get(), get(), androidContext()) }
    viewModel { CategoryViewModel(get()) }
    viewModel { DynamicViewModel(get()) }
    viewModel { LiveViewModel(get()) }
    viewModel { LiveListViewModel(get()) }
    viewModel { LiveRecommendViewModel(get()) }
    viewModel { MeListViewModel(get()) }
    viewModel { MeViewModel(get(), get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { LivePlayerViewModel(get()) }
    viewModel { SeriesDetailViewModel(get(), get()) }
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
