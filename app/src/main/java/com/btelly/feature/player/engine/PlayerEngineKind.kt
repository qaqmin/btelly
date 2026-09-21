package com.btelly.feature.player.engine

import android.content.Context
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform

/**
 * 播放器内核类型。设置页可切换，切换后**下次起播生效**（正在播放的会话不受影响），
 * 与上游实现的"播放器内核"行为对齐。
 */
enum class PlayerEngineKind(val prefValue: String, val displayName: String) {
    EXOPLAYER("exoplayer", "ExoPlayer"),
    IJKPLAYER("ijkplayer", "IjkPlayer");

    companion object {
        private const val TAG = "PlayerEngineKind"
        const val KEY_PLAYER_ENGINE = "player_engine"
        private val DEFAULT = EXOPLAYER

        fun fromPrefValue(value: String?): PlayerEngineKind =
            when (value?.trim()) {
                IJKPLAYER.prefValue, IJKPLAYER.displayName -> IJKPLAYER
                EXOPLAYER.displayName -> EXOPLAYER
                else -> DEFAULT
            }

        private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

        fun load(context: Context? = null): PlayerEngineKind {
            return runCatching { fromPrefValue(appSettings.getCachedString(KEY_PLAYER_ENGINE)) }
                .getOrElse {
                    AppLog.w(TAG, "load player engine failed: ${it.message}")
                    DEFAULT
                }
        }

        fun save(context: Context, kind: PlayerEngineKind) {
            runCatching { appSettings.putStringAsync(KEY_PLAYER_ENGINE, kind.prefValue) }
                .onFailure { AppLog.w(TAG, "save player engine failed: ${it.message}") }
        }
    }
}
