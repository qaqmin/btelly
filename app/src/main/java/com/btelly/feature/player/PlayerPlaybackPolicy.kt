package com.btelly.feature.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal object PlayerPlaybackPolicy {

    /** 通用入口：ijk 门面等纯 Player 实现只应用接口级策略（音频属性/焦点）。 */
    fun apply(player: Player) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttributes, true)
    }

    /** ExoPlayer 专属：额外开启"拔出耳机自动降噪暂停"。 */
    fun apply(player: ExoPlayer) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttributes, true)
        player.setHandleAudioBecomingNoisy(true)
    }
}
