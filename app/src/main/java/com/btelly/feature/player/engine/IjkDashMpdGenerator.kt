package com.btelly.feature.player.engine

import java.util.Locale

/**
 * 为 ijk（dashdemux）生成单 Representaion 的 on-demand DASH MPD。
 *
 * B 站 DASH 是双轨分离（video m4s + audio m4s），ijk 无法直接消费 Media3 风格的多轨
 * MPD 播放流程，这里把"选定的一个视频轨 + 一个音轨"合成为一份本地 MPD 文件：
 * 轨道引用远端 CDN URL，SegmentBase 用 byte-range 索引（Initialization + indexRange）。
 * 参考实现：上游实现 DashMpdGenerator（v0.1.18+ 实测可用）。
 */
object IjkDashMpdGenerator {

    fun buildOnDemandMpd(
        source: IjkPlaybackSource.Dash,
        videoBaseUrl: String = source.video.url,
        audioBaseUrl: String = source.audio.url,
    ): String {
        val durationAttr = source.durationMs.takeIf { it > 0 }?.let { formatMpdDuration(it) }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
            append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            append("type=\"static\" minBufferTime=\"PT1.5S\"")
            if (durationAttr != null) append(" mediaPresentationDuration=\"").append(durationAttr).append("\"")
            append(">\n")
            append("  <Period start=\"PT0S\"")
            if (durationAttr != null) append(" duration=\"").append(durationAttr).append("\"")
            append(">\n")

            appendAdaptationSet(
                contentType = "video",
                track = source.video,
                representationId = "video_${source.video.id}_${source.video.codecId}",
                baseUrl = videoBaseUrl,
            )
            appendAdaptationSet(
                contentType = "audio",
                track = source.audio,
                representationId = "audio_${source.audio.id}",
                baseUrl = audioBaseUrl,
            )

            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun StringBuilder.appendAdaptationSet(
        contentType: String,
        track: IjkPlaybackSource.Track,
        representationId: String,
        baseUrl: String,
    ) {
        append("    <AdaptationSet contentType=\"").append(xmlEscapeAttr(contentType)).append("\"")
        append(" mimeType=\"").append(xmlEscapeAttr(track.mimeType)).append("\">\n")
        appendRepresentation(track, representationId, baseUrl)
        append("    </AdaptationSet>\n")
    }

    private fun StringBuilder.appendRepresentation(
        track: IjkPlaybackSource.Track,
        representationId: String,
        baseUrl: String,
    ) {
        append("      <Representation id=\"").append(xmlEscapeAttr(representationId)).append("\"")
        append(" mimeType=\"").append(xmlEscapeAttr(track.mimeType.trim().ifBlank { "video/mp4" })).append("\"")
        if (track.bandwidth > 0L) append(" bandwidth=\"").append(track.bandwidth).append("\"")
        track.codecs.trim().takeIf { it.isNotBlank() }?.let { codecs ->
            append(" codecs=\"").append(xmlEscapeAttr(codecs)).append("\"")
        }
        if (track.width > 0) append(" width=\"").append(track.width).append("\"")
        if (track.height > 0) append(" height=\"").append(track.height).append("\"")
        track.frameRate.trim().takeIf { it.isNotBlank() }?.let { fr ->
            append(" frameRate=\"").append(xmlEscapeAttr(fr)).append("\"")
        }
        append(">\n")

        append("        <BaseURL>").append(xmlEscapeText(baseUrl)).append("</BaseURL>\n")
        if (track.indexRange.isNotBlank() || track.initialization.isNotBlank()) {
            append("        <SegmentBase indexRange=\"").append(xmlEscapeAttr(track.indexRange)).append("\">\n")
            append("          <Initialization range=\"").append(xmlEscapeAttr(track.initialization)).append("\" />\n")
            append("        </SegmentBase>\n")
        }
        append("      </Representation>\n")
    }

    private fun xmlEscapeText(value: String): String = buildString(value.length + 16) {
        for (ch in value.trim()) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    private fun xmlEscapeAttr(value: String): String = buildString(value.length + 16) {
        for (ch in value.trim()) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun formatMpdDuration(durationMs: Long): String {
        val ms = durationMs.coerceAtLeast(0L)
        val sec = ms / 1000L
        val remMs = (ms % 1000L).toInt().coerceAtLeast(0)
        return if (remMs == 0) {
            "PT${sec}S"
        } else {
            val v = sec.toDouble() + (remMs.toDouble() / 1000.0)
            // 避免科学计数法
            val fixed = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
            "PT${fixed}S"
        }
    }
}
