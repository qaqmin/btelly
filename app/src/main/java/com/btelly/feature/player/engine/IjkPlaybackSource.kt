package com.btelly.feature.player.engine

import com.btelly.core.common.log.AppLog
import com.btelly.core.common.media.VideoCodecSupport
import com.btelly.feature.player.settings.PlayerSettingsStore
import com.btelly.model.player.Dash
import com.btelly.model.player.DashAudio
import com.btelly.model.player.DashVideo
import com.btelly.model.player.PlayInfoModel
import com.btelly.model.video.quality.VideoCodecEnum

/**
 * IjkPlayer 内核的播放源模型。
 *
 * 与 ExoPlayer 侧的 MediaSource 抽象解耦：ijk 不理解 Media3 的 Timeline/Track 体系，
 * 只需要"一个视频轨 + 一个音轨"（DASH 双轨，经本地 MPD 合流）或"一个直连 URL"
 * （直播 FLV/HLS、durl 渐进式）。
 */
sealed class IjkPlaybackSource {

    /** 音视频轨描述，含 SegmentBase（MPD 按 byte-range 索引流）。 */
    data class Track(
        val url: String,
        val backupUrls: List<String> = emptyList(),
        val id: Int,
        val codecId: Int,
        val codecs: String,
        val bandwidth: Long,
        val mimeType: String,
        val initialization: String,
        val indexRange: String,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: String = "",
    )

    /** DASH 双轨源：单视频轨 + 单音轨，由 [IjkDashMpdGenerator] 生成单 Representaion MPD。 */
    data class Dash(
        val video: Track,
        val audio: Track,
        val durationMs: Long,
    ) : IjkPlaybackSource()

    /** 直连源：直播 FLV/HLS 或 durl 渐进式流。 */
    data class Direct(
        val url: String,
        val durationMs: Long = 0L,
        val isLive: Boolean = false,
    ) : IjkPlaybackSource()

    companion object {
        private const val TAG = "IjkPlaybackSource"
    }
}

/**
 * 从 B视机 已有的 playinfo 管线（含风控/试看兜底）产物中挑选 ijk 播放轨。
 *
 * 选轨策略与 Exo 侧对齐：优先用户设置的默认清晰度，编码按用户偏好 + 硬解支持排序，
 * 音轨优先默认音质设置。DASH 缺 SegmentBase 或 playinfo 只有 durl 时回退渐进式直连源。
 */
object IjkPlaybackSourceFactory {

    private const val TAG = "IjkPlaybackSource"

    fun buildFromPlayInfo(playInfo: PlayInfoModel, engine: PlayerEngineKind): IjkPlaybackSource? {
        if (engine != PlayerEngineKind.IJKPLAYER) return null
        return buildFromPlayInfo(playInfo)
    }

    fun buildFromPlayInfo(playInfo: PlayInfoModel): IjkPlaybackSource? {
        val dash: Dash? = playInfo.dash
        if (dash != null && !dash.video.isNullOrEmpty()) {
            buildDashSource(playInfo, dash)?.let { return it }
        }
        // durl 渐进式（老番剧 / 部分试看流 / dash 解析失败）
        val durl = playInfo.durl.orEmpty().firstOrNull { it.url.isNotBlank() }
        if (durl != null) {
            return IjkPlaybackSource.Direct(url = durl.url, durationMs = playInfo.timeLength)
        }
        AppLog.w(TAG, "ijk source build failed: no dash/durl playable, quality=${playInfo.quality}")
        return null
    }

    private fun buildDashSource(
        playInfo: PlayInfoModel,
        dash: Dash,
    ): IjkPlaybackSource.Dash? {
        val settings = PlayerSettingsStore.load(null)
        val hwCodecs = runCatching { VideoCodecSupport.getHardwareSupportedCodecs() }.getOrDefault(emptySet())
        val videos = dash.video.orEmpty().filter { it.realBaseUrl.isNotBlank() && it.realSegmentBase != null }
        val audios = dash.audio.orEmpty().filter { it.realBaseUrl.isNotBlank() && it.realSegmentBase != null }
        if (videos.isEmpty() || audios.isEmpty()) return null

        val video = pickVideoTrack(videos, settings.defaultVideoQualityId, settings.defaultVideoCodec, hwCodecs)
            ?: return null
        val audio = pickAudioTrack(audios, settings.defaultAudioQualityId)
            ?: return null

        val durationMs = dash.duration.takeIf { it > 0 } ?: playInfo.timeLength
        return IjkPlaybackSource.Dash(
            video = video.toTrack(),
            audio = audio.toTrack(),
            durationMs = durationMs,
        )
    }

    private fun pickVideoTrack(
        videos: List<DashVideo>,
        preferredQn: Int?,
        preferredCodec: VideoCodecEnum?,
        hwCodecs: Set<VideoCodecEnum>,
    ): DashVideo? {
        val sorted = videos.sortedWith(
            compareByDescending<DashVideo> { it.id }
                .thenByDescending { it.bandwidth }
        )
        // 用户选了默认清晰度：先按清晰度过滤（取该档位下硬解/编码偏好最优的编码），
        // 过滤后为空（服务端没下发该档）则退回全量排序取最优。
        val qnPool = preferredQn?.let { qn -> sorted.filter { it.id == qn } }?.ifEmpty { sorted } ?: sorted
        val codecPreferred = qnPool.filter { preferredCodec == null || VideoCodecEnum.fromId(it.codecId) == preferredCodec }
        val hwPreferred = (codecPreferred.ifEmpty { qnPool }).filter { hwCodecs.contains(VideoCodecEnum.fromId(it.codecId)) }
        return hwPreferred.firstOrNull()
            ?: codecPreferred.firstOrNull()
            ?: qnPool.firstOrNull()
    }

    private fun pickAudioTrack(
        audios: List<DashAudio>,
        preferredAudioId: Int?,
    ): DashAudio? {
        val sorted = audios.sortedByDescending { it.id }
        preferredAudioId?.let { id -> sorted.firstOrNull { it.id == id } }?.let { return it }
        return sorted.firstOrNull()
    }

    private fun DashVideo.toTrack() = IjkPlaybackSource.Track(
        url = realBaseUrl,
        backupUrls = realBackupUrl.orEmpty(),
        id = id,
        codecId = codecId,
        codecs = codecs,
        bandwidth = bandwidth,
        mimeType = realMimeType.ifBlank { "video/mp4" },
        initialization = realSegmentBase?.initialization.orEmpty(),
        indexRange = realSegmentBase?.realIndexRange.orEmpty(),
        width = width,
        height = height,
        frameRate = realFrameRate,
    )

    private fun DashAudio.toTrack() = IjkPlaybackSource.Track(
        url = realBaseUrl,
        backupUrls = realBackupUrl.orEmpty(),
        id = id,
        codecId = codecId,
        codecs = codecs,
        bandwidth = bandwidth,
        mimeType = realMimeType.ifBlank { "audio/mp4" },
        initialization = realSegmentBase?.initialization.orEmpty(),
        indexRange = realSegmentBase?.realIndexRange.orEmpty(),
    )
}
