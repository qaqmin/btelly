package com.btelly.feature.player.view

import com.btelly.model.video.quality.VideoQuality
import com.btelly.model.video.quality.AudioQuality
import com.btelly.model.video.quality.VideoCodecEnum

interface OnPlayerSettingChange {
    fun onVideoQualityChange(quality: VideoQuality)
    fun onAudioQualityChange(quality: AudioQuality)
    fun onPlaybackSpeedChange(speed: Float)
    fun onSubtitleChange(position: Int)
    fun onVideoCodecChange(codec: VideoCodecEnum)
    fun onAspectRatioChange(ratio: Int)
    fun onScreenMirrorChange(enabled: Boolean) {}
    fun onLiveQualityChange(qn: Int) {}
}
