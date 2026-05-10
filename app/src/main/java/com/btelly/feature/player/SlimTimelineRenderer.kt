package com.btelly.feature.player

import androidx.core.view.isVisible
import com.btelly.feature.player.sponsor.SponsorProgressMarkerView
import com.btelly.feature.player.sponsor.SponsorSegment

class SlimTimelineRenderer(
    private val markerView: SponsorProgressMarkerView,
    private val shouldShowProvider: () -> Boolean
) : TimelineRenderer {

    private var active = false

    override fun show(positionMs: Long, durationMs: Long) {
        active = shouldShowProvider()
        if (!active) {
            markerView.isVisible = false
            return
        }
        markerView.isVisible = true
        markerView.setProgress(positionMs, durationMs)
    }

    override fun showPreview(targetPositionMs: Long, durationMs: Long) {
        active = true
        markerView.isVisible = true
        markerView.setProgress(targetPositionMs, durationMs)
    }

    override fun hide() {
        active = false
        markerView.isVisible = false
    }

    override fun isActive(): Boolean = active

    fun setSegments(segments: List<SponsorSegment>) {
        markerView.setSegments(segments)
    }

    fun setSponsorDuration(durationMs: Long) {
        markerView.setSponsorDuration(durationMs)
    }
}
