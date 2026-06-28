package com.kuaishou.akdanmaku.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.btelly.core.common.log.AppLog

class DanmakuView @JvmOverloads constructor(
  context: Context?,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : View(
  context,
  attrs,
  defStyleAttr
) {

  var danmakuPlayer: DanmakuPlayer? = null
  internal val displayer: ViewDisplayer = ViewDisplayer()
  private var lastDrawAtMs = 0L
  private var drawPerfWindowStartedAtMs = 0L
  private var drawPerfFrames = 0
  private var drawPerfSlowFrames = 0
  private var drawPerfTotalMs = 0L
  private var drawPerfMaxMs = 0L
  private var drawPerfMaxIntervalMs = 0L

  init {
    context?.resources?.displayMetrics?.let { metrics ->
      displayer.density = metrics.density
      @Suppress("DEPRECATION")
      displayer.scaleDensity = metrics.scaledDensity
      displayer.densityDpi = metrics.densityDpi
    }
  }

  override fun onDraw(canvas: Canvas) {
    val startedAtMs = SystemClock.elapsedRealtime()
    val width = measuredWidth
    val height = measuredHeight
    if (width == 0 || height == 0) return
    danmakuPlayer?.notifyDisplayerSizeChanged(width, height)
    // 防挡蒙版的 PorterDuff 合成统一交给父级 DanmakuMaskHostLayout，
    // 这里只负责把弹幕画到上层 canvas，不再单独 saveLayer。
    danmakuPlayer?.draw(canvas)
    val costMs = SystemClock.elapsedRealtime() - startedAtMs
    val intervalMs = if (lastDrawAtMs > 0L) startedAtMs - lastDrawAtMs else 0L
    lastDrawAtMs = startedAtMs
    recordDrawPerf(costMs, intervalMs)
    if (costMs >= 16L || intervalMs >= 120L) {
      AppLog.w(
        "PlaybackPerf",
        "danmaku_draw cost=${costMs}ms interval=${intervalMs}ms size=${width}x$height " +
          (danmakuPlayer?.diagnosticSummary() ?: "player=null")
      )
    }
  }

  private fun recordDrawPerf(costMs: Long, intervalMs: Long) {
    val nowMs = SystemClock.elapsedRealtime()
    if (drawPerfWindowStartedAtMs == 0L) {
      drawPerfWindowStartedAtMs = nowMs
    }
    drawPerfFrames++
    if (costMs >= 16L || intervalMs >= 34L) {
      drawPerfSlowFrames++
    }
    drawPerfTotalMs += costMs
    drawPerfMaxMs = maxOf(drawPerfMaxMs, costMs)
    drawPerfMaxIntervalMs = maxOf(drawPerfMaxIntervalMs, intervalMs)
    val elapsedMs = nowMs - drawPerfWindowStartedAtMs
    if (elapsedMs < DRAW_PERF_SUMMARY_INTERVAL_MS) return
    val frames = drawPerfFrames.coerceAtLeast(1)
    val avgMs = drawPerfTotalMs.toFloat() / frames
    AppLog.d(
      "PlaybackPerf",
      "danmaku_draw_summary frames=$drawPerfFrames slow=$drawPerfSlowFrames " +
        "avg=${String.format(java.util.Locale.US, "%.2f", avgMs)}ms max=${drawPerfMaxMs}ms " +
        "maxInterval=${drawPerfMaxIntervalMs}ms " +
        (danmakuPlayer?.diagnosticSummary() ?: "player=null")
    )
    drawPerfWindowStartedAtMs = nowMs
    drawPerfFrames = 0
    drawPerfSlowFrames = 0
    drawPerfTotalMs = 0L
    drawPerfMaxMs = 0L
    drawPerfMaxIntervalMs = 0L
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    danmakuPlayer?.notifyDisplayerSizeChanged(w, h)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    danmakuPlayer?.notifyDisplayerSizeChanged(right - left, bottom - top)
  }

  /**
   * 局部 invalidate：只刷新弹幕实际占的竖向条带（top 到 height×screenPart），
   * 避免每帧全屏重绘（大屏 TV 上 GPU 带宽/功耗浪费）。借鉴 lite 引擎做法。
   * [screenPart] 弹幕显示区域比例（0~1），=1 时退化为全屏 invalidate。
   */
  fun invalidateDanmakuAreaOnAnimation(screenPart: Float) {
    val w = measuredWidth
    val h = measuredHeight
    if (w <= 0 || h <= 0 || screenPart >= 1f) {
      postInvalidateOnAnimation()
      return
    }
    val bottom = (h * screenPart.coerceIn(0f, 1f)).toInt().coerceIn(1, h)
    postInvalidateOnAnimation(0, 0, w, bottom)
  }

  class ViewDisplayer : DanmakuDisplayer {
    override var height: Int = 0
    override var width: Int = 0
    override var margin: Int = 8
    override var allMarginTop: Float = 0f
    override var density: Float = 1f
    override var scaleDensity: Float = 1f
    override var densityDpi: Int = 160
  }

  private companion object {
    private const val DRAW_PERF_SUMMARY_INTERVAL_MS = 2_000L
  }
}
