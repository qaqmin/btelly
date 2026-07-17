package com.btelly.feature.player

import com.btelly.model.subtitle.SubtitleInfoModel
import java.net.URI
import java.security.MessageDigest

internal fun isSubtitleRequestCurrent(
    requestCid: Long,
    requestBvid: String?,
    currentCid: Long,
    currentBvid: String?
): Boolean = requestCid == currentCid && requestBvid == currentBvid

internal fun trustedPlayerInfoSubtitleTracks(
    detailTracks: List<SubtitleInfoModel>,
    playerInfoTracks: List<SubtitleInfoModel>
): List<SubtitleInfoModel> {
    if (detailTracks.isEmpty()) return orderSubtitleTracksByPreference(
        playerInfoTracks.filter { isTrustedBilibiliSubtitleUrl(it.subtitleUrl) }
    )

    val detailTrackKeys = detailTracks.map(::subtitleTrackIdentityKey).toSet()
    return orderSubtitleTracksByPreference(playerInfoTracks.filter { track ->
        isTrustedBilibiliSubtitleUrl(track.subtitleUrl) &&
            subtitleTrackIdentityKey(track) in detailTrackKeys
    })
}

internal fun isLikelyAiSubtitleTrack(track: SubtitleInfoModel): Boolean =
    track.aiStatus > 0 || track.aiType > 0 ||
        track.lanDoc.contains("ai", ignoreCase = true) ||
        track.lanDoc.contains("自动") ||
        track.lanDoc.contains("机翻") ||
        track.lanDoc.contains("机器")

internal fun orderSubtitleTracksByPreference(tracks: List<SubtitleInfoModel>): List<SubtitleInfoModel> =
    tracks.sortedWith(
        compareByDescending<SubtitleInfoModel> { !isLikelyAiSubtitleTrack(it) }
            .thenByDescending { it.id }
            .thenBy { it.lanDoc }
    )

internal fun normalizeBilibiliSubtitleUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    return when {
        trimmed.isBlank() -> ""
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http://") -> "https://${trimmed.removePrefix("http://")}"
        else -> trimmed
    }
}

internal fun isTrustedBilibiliSubtitleUrl(rawUrl: String): Boolean {
    val uri = runCatching { URI(normalizeBilibiliSubtitleUrl(rawUrl)) }.getOrNull() ?: return false
    val host = uri.host?.lowercase().orEmpty()
    val trustedHost = host == "hdslb.com" || host.endsWith(".hdslb.com") ||
        host == "bilibili.com" || host.endsWith(".bilibili.com")
    return trustedHost && uri.path.orEmpty().contains("subtitle", ignoreCase = true)
}

internal fun subtitleTrackBindingKey(track: SubtitleInfoModel): String {
    val path = runCatching { URI(normalizeBilibiliSubtitleUrl(track.subtitleUrl)).path.orEmpty() }
        .getOrDefault("")
    return if (path.isBlank()) subtitleTrackIdentityKey(track) else "${subtitleTrackIdentityKey(track)}|$path"
}

internal fun subtitleTrackIdentityKey(track: SubtitleInfoModel): String {
    val identifier = track.idStr.takeIf { it.isNotBlank() }
        ?: track.id.takeIf { it > 0L }?.toString()
        ?: "no-id"
    return "$identifier|${track.lan.ifBlank { "unknown" }}"
}

internal fun refreshedSubtitleTrackFor(
    originalTrack: SubtitleInfoModel,
    refreshedTracks: List<SubtitleInfoModel>
): SubtitleInfoModel? = refreshedTracks.firstOrNull { track ->
    isTrustedBilibiliSubtitleUrl(track.subtitleUrl) &&
        subtitleTrackIdentityKey(track) == subtitleTrackIdentityKey(originalTrack)
}

internal fun shouldRetryPlayerInfoSubtitleTracks(
    detailTracks: List<SubtitleInfoModel>,
    playerInfoTracks: List<SubtitleInfoModel>
): Boolean = detailTracks.isNotEmpty() &&
    playerInfoTracks.isNotEmpty() &&
    trustedPlayerInfoSubtitleTracks(detailTracks, playerInfoTracks).isEmpty()

internal fun buildSubtitleCueCacheKey(
    bvid: String?,
    cid: Long,
    track: SubtitleInfoModel,
    normalizedUrl: String
): String {
    val urlHash = MessageDigest.getInstance("SHA-1")
        .digest(normalizedUrl.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
    return "${bvid.orEmpty()}:${cid.coerceAtLeast(0L)}:${subtitleTrackBindingKey(track)}:$urlHash"
}

internal fun shouldApplySubtitleLoadResult(
    activeToken: Long,
    resultToken: Long,
    requestCid: Long,
    requestBvid: String?,
    currentCid: Long,
    currentBvid: String?
): Boolean = activeToken == resultToken &&
    isSubtitleRequestCurrent(requestCid, requestBvid, currentCid, currentBvid)

internal fun shouldRetrySubtitleLoadWithPlayerInfo(httpCode: Int?): Boolean =
    httpCode in setOf(401, 403, 404, 410, 412)

internal fun shouldRefreshSubtitleTrack(
    track: SubtitleInfoModel,
    httpCode: Int?
): Boolean = !isTrustedBilibiliSubtitleUrl(track.subtitleUrl) ||
    shouldRetrySubtitleLoadWithPlayerInfo(httpCode)
