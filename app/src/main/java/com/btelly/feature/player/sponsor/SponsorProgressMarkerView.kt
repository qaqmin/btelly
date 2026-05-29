package com.btelly.feature.player.sponsor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SponsorProgressMarkerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var segments: List<SponsorSegment> = emptyList()
    private var durationMs: Long = 0L
    private var positionMs: Long = 0L
    private var sponsorDurationMs: Long = 0L

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0088BB.toInt() }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setSegments(segments: List<SponsorSegment>) {
        this.segments = segments
        invalidate()
    }

    fun setSponsorDuration(durationMs: Long) {
        this.sponsorDurationMs = durationMs
        invalidate()
    }

    fun setProgress(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        this.durationMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val progressEnd = SponsorProgressMarkerGeometry.playedEndPx(w, positionMs, durationMs)
        if (progressEnd > 0f) {
            canvas.drawRect(0f, 0f, progressEnd, h, playedPaint)
        }

        // 空降分段色块（在进度之上，始终可见）
        if (segments.isNotEmpty() && sponsorDurationMs > 0L) {
            for (segment in segments) {
                val left = (segment.startTimeMs.toFloat() / sponsorDurationMs) * w
                val right = (segment.endTimeMs.toFloat() / sponsorDurationMs) * w
                segmentPaint.color = (segment.categoryColor() and 0x00FFFFFFL).toInt() or 0x99000000.toInt()
                canvas.drawRect(left, 0f, right, h, segmentPaint)
            }
        }
    }
}

internal object SponsorProgressMarkerGeometry {
    fun playedEndPx(widthPx: Float, positionMs: Long, durationMs: Long): Float {
        if (widthPx <= 0f || durationMs <= 0L) {
            return 0f
        }
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * widthPx
    }
}
