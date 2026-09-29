package com.btelly.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.btelly.core.common.settings.AppSettingsDataStore
import com.btelly.event.AppEventHub
import com.btelly.network.NetworkManager
import com.btelly.network.api.ApiService
import com.btelly.network.security.NetworkManagerSecurityGateway
import com.btelly.network.security.NetworkManagerWebGateway
import com.btelly.network.security.NetworkSecurityGateway
import com.btelly.network.security.NetworkWebGateway
import com.btelly.network.session.NetworkManagerSessionGateway
import com.btelly.network.session.NetworkSessionGateway
import com.btelly.network.session.SessionStateRepository
import com.btelly.repository.AllSeriesRepository
import com.btelly.repository.AuthRepository
import com.btelly.repository.FavoriteRepository
import com.btelly.repository.HomeLaneRepository
import com.btelly.repository.PersonalFeedPrewarmer
import com.btelly.repository.LiveRepository
import com.btelly.repository.SearchRepository
import com.btelly.repository.SeriesRepository
import com.btelly.repository.UserRepository
import com.btelly.repository.VideoRepository
import com.btelly.feature.category.CategoryViewModel
import com.btelly.feature.dynamic.DynamicViewModel
import com.btelly.feature.home.HotViewModel
import com.btelly.feature.home.HotFeedRepository
import com.btelly.feature.home.HomeLaneFeedRepository
import com.btelly.feature.home.HomeLaneViewModel
import com.btelly.feature.home.RecommendDislikeFeedback
import com.btelly.feature.home.RecommendFeedRepository
import com.btelly.feature.home.RecommendViewModel
import com.btelly.feature.live.LiveListViewModel
import com.btelly.feature.live.LiveRecommendViewModel
import com.btelly.feature.live.LiveViewModel
import com.btelly.feature.me.MeListViewModel
import com.btelly.feature.me.MeViewModel
import com.btelly.feature.search.SearchViewModel
import com.btelly.feature.player.LivePlayerViewModel
import com.btelly.feature.player.danmaku.LiveDanmakuManager
import com.btelly.feature.player.douyin.DouyinModeManager
import com.btelly.ui.fragment.main.MainNavigationViewModel
import com.btelly.feature.player.VideoPlayerViewModel
import com.btelly.feature.series.SeriesDetailViewModel
import com.btelly.network.cookie.CookieManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Koin DI 延迟加载检查结论：
// Koin 的 single {} 和 viewModel {} 默认都是延迟创建的（首次请求时才实例化），
// 当前模块声明方式已经是最佳实践，无需额外优化。
val networkModule = module {
    single<ApiService> { NetworkManager.apiService }
    single<OkHttpClient> { NetworkManager.getOkHttpClient() }
    single<CookieManager> { NetworkManager.getCookieManager() }
    // 同一实例绑定双接口：Repository/播放内核用 NetworkSessionGateway，UI 层只注入 SessionStateRepository
    single<NetworkSessionGateway> { NetworkManagerSessionGateway() }
    single<SessionStateRepository> { get<NetworkSessionGateway>() as SessionStateRepository }
    single<NetworkSecurityGateway> { NetworkManagerSecurityGateway() }
    single<NetworkWebGateway> { NetworkManagerWebGateway() }
    factory(named("noCookie")) { NetworkManager.noCookieApiService }
}

val repositoryModule = module {
    single { com.btelly.repository.remote.AllSeriesRepository(get()) }
    single { com.btelly.repository.remote.AuthRepository(get()) }
    single { com.btelly.repository.remote.TvAuthRepository(get(named("noCookie"))) }
    single { com.btelly.repository.remote.FavoriteRepository(get(), get(), get()) }
    single { com.btelly.repository.comment.CommentRepository(get(), get(), get()) }
    single { com.btelly.repository.remote.HomeLaneRepository(get(), get(), get(), get()) }
    single { com.btelly.repository.remote.LiveRepository(get(), get()) }
    single { com.btelly.repository.remote.SearchRepository(get(), get()) }
    single { com.btelly.repository.remote.SeriesRepository(get(), get(), get()) }
    single { com.btelly.repository.remote.VideoRepository(get(), get(), get()) }
    single { AllSeriesRepository(get()) }
    single { AuthRepository(get()) }
    single { FavoriteRepository(get()) }
    single { HomeLaneRepository(get()) }
    single { LiveRepository(get()) }
    single { SearchRepository(get()) }
    single { SeriesRepository(get()) }
    single { VideoRepository(get(), get()) }
    single { UserRepository(get(), get(), get()) }
    single { PersonalFeedPrewarmer(get(), get(), get()) }
    single { RecommendFeedRepository(androidContext()) }
    single { HotFeedRepository() }
    single { HomeLaneFeedRepository(get()) }
}

@OptIn(UnstableApi::class)
val viewModelModule = module {
    single { RecommendDislikeFeedback(get(), get()) }
    viewModel { RecommendViewModel(get(), get(), androidContext()) }
    viewModel { HotViewModel(get(), get(), androidContext()) }
    viewModel { (type: Int) -> HomeLaneViewModel(type, get()) }
    viewModel { MainNavigationViewModel(get()) }
    viewModel { VideoPlayerViewModel(get(), get(), get(), get(), get(), get(), get(named("noCookie")), get(), androidContext(), get()) }
    viewModel { CategoryViewModel(get()) }
    viewModel { DynamicViewModel(get()) }
    viewModel { LiveViewModel(get()) }
    viewModel { LiveListViewModel(get()) }
    viewModel { LiveRecommendViewModel(get()) }
    viewModel { MeListViewModel(get(), get()) }
    viewModel { MeViewModel(get(), get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { LivePlayerViewModel(get(), LiveDanmakuManager(get(), get(), get())) }
    viewModel { SeriesDetailViewModel(get(), get()) }
}

val eventModule = module {
    single { AppEventHub() }
    single { AppSettingsDataStore(androidContext()) }
    single { DouyinModeManager(androidContext(), get(), get(), get(named("noCookie")), get()) }
}

val appModules = listOf(
    networkModule,
    repositoryModule,
    eventModule,
    viewModelModule
)
