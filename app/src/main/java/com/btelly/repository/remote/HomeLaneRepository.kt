package com.btelly.repository.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.btelly.model.lane.HomeLaneHeader
import com.btelly.model.lane.HomeLanePage
import com.btelly.model.lane.HomeLaneSection
import com.btelly.model.lane.LaneHeaderModel
import com.btelly.model.lane.LaneInfoModel
import com.btelly.model.lane.LaneItemModel
import com.btelly.model.series.BadgeInfoModel
import com.btelly.model.series.SeriesModel
import com.btelly.model.series.SeriesType
import com.btelly.model.series.timeline.SeriesTimeLineModel
import com.btelly.model.series.timeline.TimeLineADayModel
import com.btelly.network.api.ApiService
import com.btelly.network.session.NetworkSessionGateway
import com.btelly.repository.UserRepository
import com.btelly.core.common.log.AppLog
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class HomeLaneRepository(
    private val apiService: ApiService,
    private val seriesRepository: SeriesRepository,
    private val userRepository: UserRepository,
    private val sessionGateway: NetworkSessionGateway
) {

    companion object {
        private const val TAG = "HomeLaneRepository"
        const val TYPE_ANIMATION = 1
        const val TYPE_CINEMA = 2
        private const val FOLLOW_SECTION_PAGE_SIZE = 12
    }

    suspend fun getHomeLanes(
        type: Int,
        cursor: Long = 0,
        isRefresh: Boolean = true
    ): Result<HomeLanePage> {
        return try {
            Result.success(fetchReferenceAlignedHomeLanes(type = type, cursor = cursor, isRefresh = isRefresh))
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            AppLog.e(
                TAG,
                "getHomeLanes failure: type=$type, cursor=$cursor, isRefresh=$isRefresh, message=${throwable.message}",
                throwable
            )
            Result.failure(throwable)
        }
    }

    private suspend fun fetchReferenceAlignedHomeLanes(
        type: Int,
        cursor: Long,
        isRefresh: Boolean
    ): HomeLanePage = coroutineScope {
        val totalStartMs = SystemClock.elapsedRealtime()
        if (cursor > 0L || !isRefresh) {
            val legacyPage = fetchLegacyHomeLanes(type = type, cursor = cursor, isRefresh = isRefresh)
            AppLog.i(
                TAG,
                "homeLane fetch total type=$type cursor=$cursor refresh=$isRefresh elapsed=${SystemClock.elapsedRealtime() - totalStartMs}ms sections=${legacyPage.sections.size}"
            )
            return@coroutineScope legacyPage
        }

        val followDeferred = async {
            val startMs = SystemClock.elapsedRealtime()
            try {
                fetchMyFollowingSection(type).also { section ->
                    AppLog.i(
                        TAG,
                        "homeLane follow section type=$type elapsed=${SystemClock.elapsedRealtime() - startMs}ms has=${section != null}"
                    )
                }
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                AppLog.e(
                    TAG,
                    "fetchReferenceAlignedHomeLanes follow section failure: type=$type, elapsed=${SystemClock.elapsedRealtime() - startMs}ms, message=${throwable.message}",
                    throwable
                )
                null
            }
        }

        val timelineDeferred = if (type == TYPE_ANIMATION) {
            async {
                val startMs = SystemClock.elapsedRealtime()
                try {
                    getAnimationTimelineSection().getOrNull().also { section ->
                        AppLog.i(
                            TAG,
                            "homeLane timeline section type=$type elapsed=${SystemClock.elapsedRealtime() - startMs}ms has=${section != null}"
                        )
                    }
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    AppLog.e(
                        TAG,
                        "fetchReferenceAlignedHomeLanes timeline failure: type=$type, elapsed=${SystemClock.elapsedRealtime() - startMs}ms, message=${throwable.message}",
                        throwable
                    )
                    null
                }
            }
        } else {
            null
        }

        val legacyStartMs = SystemClock.elapsedRealtime()
        val legacyPage = fetchLegacyHomeLanes(type = type, cursor = cursor, isRefresh = isRefresh)
        AppLog.i(
            TAG,
            "homeLane legacy type=$type elapsed=${SystemClock.elapsedRealtime() - legacyStartMs}ms sections=${legacyPage.sections.size}"
        )
        val sections = legacyPage.sections.toMutableList()

        val followSection = followDeferred.await()

        sections.removeAll { it.isFollowSection() }

        if (followSection != null) {
            sections.add(0, followSection)
        }

        timelineDeferred?.await()?.let { timelineSection ->
            val insertIndex = if (followSection != null) 1 else minOf(1, sections.size)
            sections.add(insertIndex, timelineSection)
        }

        AppLog.i(
            TAG,
            "homeLane fetch total type=$type cursor=$cursor refresh=$isRefresh elapsed=${SystemClock.elapsedRealtime() - totalStartMs}ms sections=${sections.size}"
        )
        return@coroutineScope legacyPage.copy(sections = sections)
    }

    suspend fun getAnimationTimelineSection(): Result<HomeLaneSection?> {
        return try {
            val response = apiService.getSeriesTimeLine(
                type = SeriesType.ANIME,
                before = 6,
                after = 6
            )
            if (response.code != 0) {
                throw IllegalStateException(response.message)
            }
            Result.success(buildTimelineSection(response.result))
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "getAnimationTimelineSection failure: ${throwable.message}", throwable)
            Result.failure(throwable)
        }
    }

    private suspend fun fetchLegacyHomeLanes(
        type: Int,
        cursor: Long,
        isRefresh: Boolean
    ): HomeLanePage {
        val apiRefresh = if (isRefresh) 0 else 1
        val response = sessionGateway.executeWithRiskControlRetry(
            key = "home_lane_$type",
            source = "homeLane.fetchLegacy"
        ) {
            when (type) {
                TYPE_CINEMA -> apiService.getCinema(
                    isRefresh = apiRefresh,
                    cursor = cursor
                )
                else -> apiService.getAnimations(
                    isRefresh = apiRefresh,
                    cursor = cursor
                )
            }
        }

        if (response.code != 0 || response.data == null) {
            throw IllegalStateException(response.message.ifEmpty { response.msg })
        }

        val modules = response.data.modules
        val hasMore = modules.size >= 20
        return HomeLanePage(
            sections = modules.mapNotNull { it.toSection() },
            nextCursor = response.data.nextCursor,
            hasMore = hasMore
        )
    }

    private suspend fun fetchMyFollowingSection(type: Int): HomeLaneSection? {
        if (!userRepository.isLoggedIn()) {
            return null
        }

        val mid = userRepository.resolveCurrentUserMid().getOrNull()
            ?.takeIf { it > 0L }
            ?: return null

        val following = seriesRepository.getMyFollowingSeries(
            type = type,
            page = 1,
            pageSize = FOLLOW_SECTION_PAGE_SIZE,
            vmid = mid
        ).getOrThrow()

        val items = following.list
            .mapNotNull { it.toFollowLaneItem() }
            .distinctBy { item -> item.seasonId to item.oid }
            .take(FOLLOW_SECTION_PAGE_SIZE)
        if (items.isEmpty()) {
            return null
        }

        return HomeLaneSection(
            title = if (type == TYPE_CINEMA) "我的追剧" else "我的追番",
            items = items,
            moreSeasonType = null,
            style = "follow",
            disableMore = true
        )
    }

    private fun LaneInfoModel.toSection(): HomeLaneSection? {
        if (items.isEmpty()) {
            return null
        }

        val moreUrl = headers.lastOrNull()?.url.orEmpty()

        return HomeLaneSection(
            title = title,
            items = items,
            headers = headers.map { it.toHeader() },
            moreSeasonType = parseMoreSeasonType(moreUrl),
            moreUrl = moreUrl,
            style = style,
            moduleId = moduleId
        )
    }

    private fun LaneHeaderModel.toHeader(): HomeLaneHeader {
        return HomeLaneHeader(
            title = title,
            url = url
        )
    }

    private fun buildTimelineSection(days: List<TimeLineADayModel>): HomeLaneSection? {
        if (days.size < 10) {
            return null
        }

        val normalizedDays = days.map { day ->
            day.copy(
                episodes = day.episodes.map { episode ->
                    episode.copy(dayOfWeek = day.dayOfWeek)
                }
            )
        }
        val recentEpisodes = normalizedDays
            .flatMap { it.episodes }
            .filter { it.published == 1 }
            .sortedByDescending { it.pubTs }

        val recentDay = TimeLineADayModel(
            episodes = recentEpisodes
        )

        return HomeLaneSection(
            title = "",
            timelineDays = listOf(recentDay) + normalizedDays,
            style = "timeline"
        )
    }

    private fun parseMoreSeasonType(url: String): Int? {
        if (!url.contains("index_type")) {
            return null
        }

        val indexType = Regex("(?<=index_type=).*?(?=&|$)")
            .find(url)
            ?.value
            ?.toIntOrNull()
            ?: return null

        if (indexType == SeriesType.ANIME) {
            val area = Regex("(?<=area=).*?(?=&|$)")
                .find(url)
                ?.value
                .orEmpty()
            if (area == "1,6,7") {
                return SeriesType.CHINA_ANIME
            }
        }
        return indexType
    }






    private fun JsonObject.toSeriesCard(): LaneItemModel? {
        val title = string("title")
        val cover = string("cover")
        if (title.isBlank() || cover.isBlank()) {
            return null
        }
        val link = string("link")
        val parsed = parseBangumiLink(link)
        val seasonId = long("season_id").takeIf { it > 0 } ?: parsed.first
        val epId = long("episode_id").takeIf { it > 0 } ?: parsed.second
        val rankTag = string("rank_tag")
        return LaneItemModel(
            seasonId = seasonId,
            title = title,
            cover = cover,
            desc = string("desc").ifBlank { string("evaluate") },
            subTitle = string("sub_title"),
            link = link,
            oid = long("oid").takeIf { it > 0 } ?: epId,
            badgeInfo = badgeInfoOrNull()
                ?: rankTag.takeIf { it.isNotBlank() }?.let { BadgeInfoModel(text = it) }
        )
    }

    private fun SeriesModel.toFollowLaneItem(): LaneItemModel? {
        val resolvedTitle = title.ifBlank { seasonTitle }
        val resolvedCover = cover.ifBlank { horizontalCover169 }
        if (resolvedTitle.isBlank() || resolvedCover.isBlank()) {
            return null
        }

        val subtitle = progress.ifBlank { seasonTitle }
        return LaneItemModel(
            seasonId = seasonId,
            oid = firstEp.takeIf { it > 0 } ?: newEp?.id ?: firstEpInfo?.id ?: 0L,
            title = resolvedTitle,
            cover = resolvedCover,
            desc = progress.ifBlank { evaluate.ifBlank { summary } },
            subTitle = subtitle,
            link = url,
            newEp = newEp ?: firstEpInfo,
            badgeInfo = badgeInfo ?: badge.takeIf { it.isNotBlank() }?.let { BadgeInfoModel(text = it) }
        )
    }

    private fun JsonObject.badgeInfoOrNull(): BadgeInfoModel? {
        val badgeObject = get("badge_info")?.asJsonObjectOrNull()
            ?: get("badge")?.asJsonObjectOrNull()
            ?: return null
        val text = badgeObject.string("text")
        val bgColor = badgeObject.string("bg_color")
        val bgColorNight = badgeObject.string("bg_color_night")
        if (text.isBlank() && bgColor.isBlank() && bgColorNight.isBlank()) {
            return null
        }
        return BadgeInfoModel(
            text = text,
            bgColor = bgColor,
            bgColorNight = bgColorNight
        )
    }

    private fun parseBangumiLink(url: String): Pair<Long, Long> {
        val normalized = normalizeUrl(url)
        val seasonId = Regex("/ss(\\d+)").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val epId = Regex("/ep(\\d+)").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        return seasonId to epId
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            else -> "https://www.bilibili.com${if (url.startsWith("/")) url else "/$url"}"
        }
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
        return if (this != null && isJsonObject) asJsonObject else null
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key) ?: return ""
        return if (value.isJsonPrimitive) value.asString else ""
    }

    private fun JsonObject.long(key: String): Long {
        val value = get(key) ?: return 0L
        return runCatching { value.asLong }.getOrDefault(0L)
    }

    private fun JsonObject.int(key: String): Int {
        val value = get(key) ?: return 0
        return runCatching { value.asInt }.getOrDefault(0)
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? {
        val value = get(key) ?: return null
        return if (value.isJsonArray) value.asJsonArray else null
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> {
        return this?.toList().orEmpty()
    }

    private fun HomeLaneSection.isFollowSection(): Boolean {
        if (style.equals("follow", ignoreCase = true)) {
            return true
        }

        val signals = buildList {
            add(title)
            add(style)
            headers.forEach { header ->
                add(header.title)
                add(header.url)
            }
        }.map { signal ->
            signal.replace(" ", "").trim()
        }.filter { it.isNotBlank() }

        return signals.any { signal ->
            signal.contains("我的追番") ||
                signal.contains("我的追剧") ||
                signal.contains("追番") ||
                signal.contains("追剧") ||
                signal.contains("follow", ignoreCase = true)
        }
    }
}
