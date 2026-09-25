package com.btelly.feature.player

import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.btelly.feature.player.extractor.FlvHevcExtractor
import com.btelly.feature.player.engine.IjkPlaybackSource
import com.btelly.feature.player.engine.IjkPlayerEngine
import com.btelly.feature.player.engine.PlayerEngineKind
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import com.btelly.R
import com.btelly.core.common.log.AppLog
import com.btelly.databinding.FragmentLivePlayerBinding
import com.btelly.core.ui.navigation.navigateBackFromUi
import com.btelly.core.ui.system.ViewUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

@UnstableApi
class LivePlayerFragment : Fragment() {

    companion object {
        private const val TAG = "LivePlayerFragment"
        const val ARG_ROOM_ID = "room_id"
        private const val MAX_RETRY_COUNT = 3

        /** 原画档 qn（B 站直播画质_desc 10000=原画）：>= 此值的内容是未降帧的高帧率流，
         *  走 ijk；蓝光(400)/超清(250) 等是平台降帧流，走 ExoPlayer。 */
        private const val ORIGIN_QUALITY_QN = 10_000

        fun newInstance(roomId: Long): LivePlayerFragment {
            return LivePlayerFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROOM_ID, roomId)
                }
            }
        }
    }

    private var _binding: FragmentLivePlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LivePlayerViewModel by viewModel()
    private val okHttpClient: OkHttpClient by inject()

    private var player: Player? = null
    private var roomId: Long = 0L
    private var retryCount = 0
    private var isRetrying = false

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "live player error: ${error.message}", error)
            if (isRetrying) return
            if (retryCount < MAX_RETRY_COUNT) {
                retryCount++
                isRetrying = true
                AppLog.d(TAG, "auto retry ($retryCount/$MAX_RETRY_COUNT) for error type=${error.errorCodeName}")
                binding.textError.visibility = View.GONE
                binding.progressBar.visibility = View.VISIBLE
                val delayMs = retryCount * 1000L
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(delayMs)
                    if (_binding == null) return@launch
                    val needRebuild = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        // 看门狗上报的假死（prepare/播放停滞）：ijk 原生消息循环可能已死，
                        // 仅重挂 dataSource 不保证恢复，必须整个引擎重建。
                        PlaybackException.ERROR_CODE_TIMEOUT -> true
                        else -> false
                    }
                    if (needRebuild) rebuildPlayer()
                    viewModel.retryLiveStream(roomId)
                    isRetrying = false
                }
            } else {
                binding.textError.text = getString(R.string.live_retry_failed_format, error.message, MAX_RETRY_COUNT)
                binding.textError.visibility = View.VISIBLE
            }
            syncPlaybackEnvironment()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlaybackEnvironment()
            when (playbackState) {
                Player.STATE_BUFFERING -> binding.playerView.pauseDanmaku()
                Player.STATE_READY -> {
                    retryCount = 0
                    isRetrying = false
                    if (player?.playWhenReady == true) binding.playerView.resumeDanmaku()
                }
                Player.STATE_ENDED -> binding.playerView.stopDanmaku()
                Player.STATE_IDLE -> binding.playerView.pauseDanmaku()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackEnvironment()
            if (isPlaying) {
                retryCount = 0
                binding.playerView.resumeDanmaku()
            } else if (player?.playbackState != Player.STATE_BUFFERING) {
                binding.playerView.pauseDanmaku()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val fmt = (player as? ExoPlayer)?.videoFormat
            AppLog.i(
                TAG,
                "live video size=${videoSize.width}x${videoSize.height} " +
                    "fps=${fmt?.frameRate} bitrate=${fmt?.bitrate} codec=${fmt?.sampleMimeType}"
            )
        }
    }

    /**
     * 丢帧 + 音画同步诊断（HEVC/ExoPlayer 直播路，只读遥测，零行为变更）：
     * - dropped3s：渲染器主动丢帧（≈0=解码器供帧不足；占比高=画面落后于声音在追同步）
     * - vproc：最近一次帧处理耗时（解码→送显开销，持续增长=供帧链路劣化）
     * - buf：缓冲余量 totalBufferedDurationMs（趋零=供帧饥饿，声音会拖画面越远）
     * - pos：当前播放位置（音频主时钟，供各采样点对齐）
     * 3 秒一行 live_av_sync（回调触发 + 心跳兜底，无丢帧期间也连续采样）；音频 underrun 立即一行。
     * 诊断期临时遥测，确认后整体移除。
     */
    private val videoStatsListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        var droppedFrames = 0
            private set
        var lastVprocUs = 0L
            private set

        /** 取出并清零累计丢帧数，供 [logSyncSample] 按 3 秒窗口采样。 */
        fun consumeDroppedFrames(): Int {
            val v = droppedFrames
            droppedFrames = 0
            return v
        }

        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            this.droppedFrames += droppedFrames
            logSyncSample(eventTime, elapsedMs)
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            videoFrameProcessingOffsetUs: Long,
            bufferTimestampMs: Int,
        ) {
            lastVprocUs = videoFrameProcessingOffsetUs
            logSyncSample(eventTime, 0)
        }

        override fun onAudioUnderrun(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            actualWrittenBytes: Int,
            bufferWrittenUs: Long,
            targetBufferWrittenUs: Long,
        ) {
            AppLog.w(TAG, "live_av_sync audio_underrun bufUs=$bufferWrittenUs targetUs=$targetBufferWrittenUs")
        }
    }

    private var lastSyncLogMs = 0L

    /** 3 秒节流采样。eventTime 为空时退化为读 player 字段（心跳路径）。 */
    private fun logSyncSample(
        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime?,
        elapsedMs: Long,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSyncLogMs < 3_000L) return
        lastSyncLogMs = now
        val dropped = videoStatsListener.consumeDroppedFrames()
        val p = player as? androidx.media3.exoplayer.ExoPlayer
        val bufMs = eventTime?.totalBufferedDurationMs
            ?: p?.let { it.bufferedPosition - it.currentPosition }
            ?: 0L
        val posMs = eventTime?.currentPlaybackPositionMs ?: p?.currentPosition ?: 0L
        AppLog.w(
            TAG,
            "live_av_sync dropped3s=$dropped elapsed=${elapsedMs}ms " +
                "vproc=${videoStatsListener.lastVprocUs / 1000}ms buf=${bufMs}ms pos=${posMs}ms"
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLivePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 音画遥测心跳（诊断期临时，零行为变更）：无丢帧/偏移回调期间也保持 3 秒连续采样；
        // 仅 ExoPlayer（HEVC）路采样，ijk 路的漂移由 IjkPlayerEngine 自带遥测覆盖。
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(3_000L)
                if (player is androidx.media3.exoplayer.ExoPlayer) {
                    logSyncSample(null, 0)
                }
            }
        }
        arguments?.let { args ->
            roomId = args.getLong(ARG_ROOM_ID, -1L)
        }
        val textClock = binding.textClock
        textClock.alpha = 0f
        textClock.translationY = -textClock.height.toFloat().coerceAtLeast(200f)
        binding.playerView.setControllerVisibilityListener(object : com.btelly.feature.player.view.MyPlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                val animate = textClock.animate().setDuration(250L)
                when (visibility) {
                    View.VISIBLE -> animate.translationY(0f).alpha(1f).setListener(null)
                    View.INVISIBLE -> animate.translationY(-textClock.height.toFloat()).alpha(0f).setListener(null)
                    View.GONE -> {
                        textClock.translationY = -textClock.height.toFloat()
                        textClock.alpha = 0f
                    }
                }
            }
        })
        setupPlayer()
        setupObservers()
        if (roomId > 0) {
            viewModel.loadLiveStream(roomId)
        }
    }

    private fun setupPlayer(useIjk: Boolean = false) {
        binding.playerView.setupDanmakuEngine()
        val liveHeaders = mapOf(
            "Origin" to "https://live.bilibili.com/",
            "Referer" to if (roomId > 0L) {
                "https://live.bilibili.com/$roomId"
            } else {
                "https://live.bilibili.com"
            }
        )
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
            )
            .setDefaultRequestProperties(liveHeaders)
        // 恢复直播侧原始配置：自定义 FlvHevcExtractor（HEVC 直播房间需要）+ 直播专用
        // LoadControl + mime 提示。LiveConfiguration 保持移除（其修的"seek PPT"实为
        // 弹幕时钟问题，已另修）。内核按画质路由：原画(60fps 内容)走 ijk（ExoPlayer 在
        // progressive-live 60fps 上实测仅 8~10fps，ijk 同解码器 ~50fps）；蓝光/超清是
        // 平台降帧后的 30fps 流，走 ExoPlayer（同步优先，30fps 即内容原速）。
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 30_000, 1_000, 2_000)
            .build()
        val extractorsFactory = ExtractorsFactory {
            DefaultExtractorsFactory().createExtractors().map { extractor ->
                if (extractor is FlvExtractor) FlvHevcExtractor() else extractor
            }.toTypedArray()
        }
        player = if (useIjk) {
            // 原画路由：ijk 同步 MediaCodec 路径，同解码器 ~50fps（ExoPlayer 仅 8~10fps）。
            // 漂移由 IjkPlayerEngine 内建的 driftMs 软重锚兜底。
            IjkPlayerEngine(requireContext())
        } else {
            ExoPlayer.Builder(requireContext())
                .setRenderersFactory(PlayerInstancePool.createRenderersFactory(requireContext()))
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
                .setLoadControl(loadControl)
                .build()
                .also {
                    PlayerPlaybackPolicy.apply(it)
                    if (com.btelly.feature.player.settings.PlayerSettingsStore.load(requireContext()).audioNormalize) {
                        PlayerAudioNormalizer.attach(it)
                    }
                }
        }.also {
            if (it is ExoPlayer) {
                it.addListener(playerListener)
                it.addAnalyticsListener(videoStatsListener)
            }
        }
        binding.playerView.setPlayer(player)
        binding.playerView.setControllerAutoShow(true)
        binding.playerView.showHideFfRe(false)
        binding.playerView.showHideNextPrevious(false)
        binding.playerView.showHideEpisodeButton(false)
        binding.playerView.showHideActionButton(false)
        binding.playerView.showHideRelatedButton(false)
        binding.playerView.showHideRepeatButton(false)
        binding.playerView.showHideSubtitleButton(false)
        binding.playerView.showHideDmSwitchButton(true)
        binding.playerView.setShowHideOwnerInfo(false)
        binding.playerView.showSettingButton(false)
        binding.playerView.showHideLiveSettingButton(false)
        binding.playerView.showHideTimeBar(false)
        binding.playerView.showHideTimeText(false)
        binding.playerView.showHideRefreshButton(true)
        binding.playerView.showHideMirrorButton(true)
        binding.playerView.showHideLineButton(false)
        binding.playerView.setOnVideoSettingChangeListener(object : com.btelly.feature.player.view.OnVideoSettingChangeListener {
            override fun onLiveSettings() {
                binding.playerView.showLiveQualityMenu()
            }

            override fun onLiveLineSettings() {
                binding.playerView.showLiveLineMenu()
            }

            override fun onRefresh() {
                if (roomId > 0) {
                    Toast.makeText(requireContext(), "正在刷新", Toast.LENGTH_SHORT).show()
                    viewModel.refreshLiveStream(roomId)
                }
            }

            override fun onClose() {
                navigateBackFromUi()
            }

            override fun onMirrorChange(enabled: Boolean) {
                binding.playerView.setMirrorEnabled(enabled)
            }
        })
        binding.playerView.setOnPlayerSettingChange(object : com.btelly.feature.player.view.OnPlayerSettingChange {
            override fun onVideoQualityChange(quality: com.btelly.model.video.quality.VideoQuality) {}
            override fun onAudioQualityChange(quality: com.btelly.model.video.quality.AudioQuality) {}
            override fun onPlaybackSpeedChange(speed: Float) {}
            override fun onSubtitleChange(position: Int) {}
            override fun onVideoCodecChange(codec: com.btelly.model.video.quality.VideoCodecEnum) {}
            override fun onAspectRatioChange(ratio: Int) {}
            override fun onLiveQualityChange(qn: Int) {
                viewModel.switchQuality(qn)
            }

            override fun onLiveLineChange(index: Int) {
                viewModel.switchLine(index)
            }
        })

        // 初始化直播弹幕：按持久化开关启用（此前硬编码 true，用户关弹幕后刷新会被强制打开），
        // 随后启动引擎（不等待数据）。弹幕关时同步停掉 WebSocket，避免低端盒子上
        // 空跑解析与播放线程抢 CPU。
        val dmWanted = binding.playerView.isDanmakuEnabledByPref()
        viewModel.setDanmakuStreamEnabled(dmWanted)
        binding.playerView.onDanmakuEnabledChangedListener = { enabled ->
            viewModel.setDanmakuStreamEnabled(enabled)
        }
        binding.playerView.setDanmakuEnabled(dmWanted)
        binding.playerView.startLiveDanmaku()

        syncPlaybackEnvironment()
    }

    private fun rebuildPlayer(useIjk: Boolean = false) {
        player?.removeListener(playerListener)
        binding.playerView.stopDanmaku()
        (player as? ExoPlayer)?.let(PlayerAudioNormalizer::release)
        player?.release()
        player = null
        setupPlayer(useIjk = useIjk)
    }

    /**
     * 直播解码追不上时仅记录遥测，不自动降画质：用户的最终目标是原画高帧率，
     * 降画质等于从源头接受平台降帧（蓝光无 60 帧），与该目标冲突。
     * 同步由引擎侧 framedrop=1（丢迟到帧追实时）+ 软重锚兜底，此处只留观测。
     */
    private fun onLiveDecodeStarved(driftMs: Long) {
        val ladder = viewModel.qualities.value.sortedByDescending { it.qn }
        val currentQn = viewModel.selectedQuality.value?.qn ?: return
        val idx = ladder.indexOfFirst { it.qn == currentQn }
        val next = ladder.getOrNull(idx + 1)
        AppLog.w(
            TAG,
            "live_decode_starved 遥测：解码追不上 driftMs=$driftMs " +
                "当前 qn=$currentQn 下一档=${next?.qn ?: "无"}（不自动降级，仅记录）",
        )
    }

    private fun syncPlaybackEnvironment() {
        val currentPlayer = player
        val keepScreenOn = currentPlayer != null &&
            currentPlayer.playWhenReady &&
            currentPlayer.playbackState != Player.STATE_IDLE &&
            currentPlayer.playbackState != Player.STATE_ENDED
        activity?.let { ViewUtils.keepScreenOn(it, keepScreenOn) }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playUrl.collect { url ->
                url?.let {
                    binding.textError.visibility = View.GONE
                    // 内核按编码+画质路由：
                    // - HEVC 原画必须走 ExoPlayer：ijk 自带的 ffmpeg 没编 HEVC 解码器
                    //   （实测 "Could not find codec parameters ... unknown codec"，codec id 12
                    //   认不出），而 ExoPlayer 走 Android MediaCodec 直接调
                    //   OMX.allwinner.video.decoder.hevc。且 /vendor/etc/media_codecs.xml 声明
                    //   HEVC 解码器 blocks-per-second 是 AVC 的 1.87 倍、无 1080p 帧率上限，
                    //   而 AVC 解码器 performance-point-1920x1080=50（1080p 只能 sustain 50fps）。
                    // - AVC 原画（qn>=10000）走 ijk：ExoPlayer 的 AVC progressive-live 60fps
                    //   路径实测仅 8~10fps（丢帧追同步），ijk 同解码器 ~50fps。
                    // - 蓝光/超清是平台降帧的 30fps 流，走 ExoPlayer（同步、无停顿）。
                    val isHevc = it.contains("codec=1", true) ||
                        it.contains("prohevc", true) ||
                        it.contains("hevc", true)
                    val wantIjk = !isHevc &&
                        (viewModel.selectedQuality.value?.qn ?: 0) >= ORIGIN_QUALITY_QN
                    val currentIsIjk = player is IjkPlayerEngine
                    if (wantIjk != currentIsIjk) {
                        AppLog.i(TAG, "live engine route -> ${if (wantIjk) "ijk" else "exo"} (hevc=$isHevc qn=${viewModel.selectedQuality.value?.qn})")
                        rebuildPlayer(useIjk = wantIjk)
                    }
                    val p = player
                    if (p is IjkPlayerEngine) {
                        // ijk：直连源装载（setMediaItem 在门面中不可用）
                        p.onLiveDecodeStarved = { driftMs -> onLiveDecodeStarved(driftMs) }
                        p.setIjkSource(IjkPlaybackSource.Direct(url = it, isLive = true), 0L)
                        p.prepare()
                        p.playWhenReady = true
                    } else {
                        val mediaItem = MediaItem.Builder()
                            .setUri(it)
                            .setMimeType(resolveMimeType(it))
                            .build()
                        p?.setMediaItem(mediaItem)
                        p?.prepare()
                        AppLog.i(TAG, "live stream url host=${runCatching { java.net.URI(it).host }.getOrNull()}")
                        p?.playWhenReady = true
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.lines.collect { lines ->
                binding.playerView.setLiveLines(lines, viewModel.selectedLineIndex.value)
                binding.playerView.showHideLineButton(lines.size >= 2)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedLineIndex.collect { idx ->
                binding.playerView.selectLiveLine(idx)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    binding.textError.text = it
                    binding.textError.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.qualities.collect { qualities ->
                binding.playerView.setLiveQualities(qualities)
                binding.playerView.showHideLiveSettingButton(qualities.isNotEmpty())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedQuality.collect { quality ->
                quality?.let {
                    binding.playerView.selectLiveQuality(it.qn)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.roomTitle.collect { title ->
                binding.playerView.setTitle(title)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.anchorName.collect { name ->
                binding.playerView.setSubTitle(name)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveDuration.collect { duration ->
                binding.playerView.setLiveDuration(if (duration.isNotEmpty()) "直播中：$duration" else "")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.refreshEvent.collect { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        // 实时弹幕：用引擎当前时间作为 position 注入
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveDanmaku.collect { dmModel ->
                    binding.playerView.addLiveDanmaku(dmModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
        syncPlaybackEnvironment()
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
        binding.playerView.pauseDanmaku()
        syncPlaybackEnvironment()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.removeListener(playerListener)
        binding.playerView.stopDanmaku()
        binding.playerView.destroy()
        (player as? ExoPlayer)?.let(PlayerAudioNormalizer::release)
        player?.release()
        activity?.let { ViewUtils.keepScreenOn(it, false) }
        player = null
        _binding = null
    }

    private fun resolveMimeType(url: String): String? {
        val normalized = url.substringBefore('?').lowercase()
        return when {
            normalized.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            normalized.endsWith(".flv") -> MimeTypes.VIDEO_FLV
            else -> null
        }
    }
}
