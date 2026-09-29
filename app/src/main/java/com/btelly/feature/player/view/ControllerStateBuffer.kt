package com.btelly.feature.player.view

import androidx.media3.common.Player

/**
 * 控制栏（[MyPlayerControlView]）的惰性创建状态缓冲。
 *
 * 背景：控制栏是 lazy 创建的（见 `MyPlayerView.setupController`），创建之前外部就会调用
 * 一堆 `setTitle/showHideXxx/setSponsorSegments/...` 配置入口。这些值必须先攒在
 * View 侧的字段里，等控制器真正建出来时一次性灌进去（`applyPendingControllerState`）。
 *
 * 原来这些字段平铺在 MyPlayerView 里共 30 多个 `pendingXxx`，把「播放器视图」和
 * 「控制栏配置的暂存箱」两个本不相干的职责混在一起，是这个类膨胀到 3200+ 行的原因之一。
 * 这里把它们整体搬出来，行为完全不变——仍然是「存起来 → 有控制器就灌 → 无控制器就暂存」。
 *
 * 与另一类字段的区别：`MyPlayerView` 里还有 `pendingDmMaskRequest`、`pendingHoldStartRunnable`
 * 等，属于弹幕遮罩请求去重和长按手势时序，跟控制栏配置无关，**不在这里**。
 */
internal class ControllerStateBuffer {

    // ---- 首次灌入即生效的纯值 ----
    var repeatMode: Int = Player.REPEAT_MODE_OFF
    var seekSeconds: Int? = null
    var timeBarMinUpdateIntervalMs: Int =
        MyPlayerControlView.DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS
    var showMultiWindowTimeBar: Boolean = false
    var sponsorSegments: List<SponsorSegment> = emptyList()
    var sponsorDurationMs: Long = 0L

    // ---- 可为空的文本/监听 ----
    var title: String? = null
    var subTitle: String? = null
    var liveDuration: String? = null
    var videoSettingChangeListener: OnVideoSettingChangeListener? = null

    // ---- 三态可空开关：null=外部没表态，保留控制器当前状态 ----
    var simpleKeyPressEnabled: Boolean? = null
    var episodeNavigationEnabled: Pair<Boolean, Boolean>? = null
    var dmSwitchVisible: Boolean? = null
    var mirrorVisible: Boolean? = null
    var nextPreviousVisible: Boolean? = null
    var ffReVisible: Boolean? = null
    var episodeButtonVisible: Boolean? = null
    var actionButtonVisible: Boolean? = null
    var relatedButtonVisible: Boolean? = null
    var repeatButtonVisible: Boolean? = null
    var subtitleButtonVisible: Boolean? = null
    var liveSettingButtonVisible: Boolean? = null
    var refreshButtonVisible: Boolean? = null
    var lineButtonVisible: Boolean? = null
    var timeBarVisible: Boolean? = null
    var timeTextVisible: Boolean? = null
    var settingButtonVisible: Boolean? = null
    var ownerButtonVisible: Boolean? = null

    /**
     * 把暂存的状态灌给（刚刚建好的）控制栏。
     *
     * 顺序与原 `MyPlayerView.applyPendingControllerState` 完全一致：纯值先行，
     * 可空文本/监听其次，最后是一批三态开关——乱序不会改变语义，但保持一致便于 diff。
     */
    fun applyTo(target: MyPlayerControlView) {
        target.setRepeatMode(repeatMode)
        title?.let(target::setTitle)
        subTitle?.let(target::setSubTitle)
        liveDuration?.let(target::setLiveDuration)
        target.setOnVideoSettingChangeListener(videoSettingChangeListener)
        seekSeconds?.let { target.setFfDuration(it.coerceAtLeast(1).toLong() * 1000L) }
        target.setTimeBarMinUpdateInterval(timeBarMinUpdateIntervalMs)
        target.setShowMultiWindowTimeBar(showMultiWindowTimeBar)
        simpleKeyPressEnabled?.let(target::setSimpleKeyPressEnabled)
        episodeNavigationEnabled?.let { target.setEpisodeNavigationEnabled(it.first, it.second) }
        dmSwitchVisible?.let(target::showHideDmSwitchButton)
        mirrorVisible?.let(target::showHideMirrorButton)
        nextPreviousVisible?.let(target::showHideNextPrevious)
        ffReVisible?.let(target::showHideFfRe)
        episodeButtonVisible?.let(target::showHideEpisodeButton)
        actionButtonVisible?.let(target::showHideActionButton)
        relatedButtonVisible?.let(target::showHideRelatedButton)
        repeatButtonVisible?.let(target::showHideRepeatButton)
        subtitleButtonVisible?.let(target::showHideSubtitleButton)
        liveSettingButtonVisible?.let(target::showHideLiveSettingButton)
        refreshButtonVisible?.let(target::showHideRefreshButton)
        lineButtonVisible?.let(target::showHideLineButton)
        timeBarVisible?.let(target::showHideTimeBar)
        timeTextVisible?.let(target::showHideTimeText)
        settingButtonVisible?.let(target::showSettingButton)
        ownerButtonVisible?.let(target::setShowHideOwnerButton)
        target.setSponsorSegments(sponsorSegments)
        target.setSponsorDuration(sponsorDurationMs)
    }
}
