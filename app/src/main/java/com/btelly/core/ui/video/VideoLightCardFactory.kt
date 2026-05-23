package com.btelly.core.ui.video

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.btelly.R
import com.btelly.core.common.log.VideoCardPerfLogger
import kotlin.math.ceil

object VideoLightCardFactory {

    fun create(parent: ViewGroup, source: String = "VideoLightCard.flat"): VideoCardViews {
        val context = parent.context
        val metrics = LightCardMetrics.get(context)
        val appName = context.getString(R.string.app_name)

        val root = FlatVideoLightCardLayout(context, metrics, source).apply {
            id = R.id.click_view
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.cell_background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val imageView = ImageView(context).apply {
            id = R.id.imageView
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.default_video)
        }
        root.addView(imageView)
        root.imageView = imageView

        val coverGradient = View(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.focus_background_round)
        }
        root.addView(coverGradient)
        root.coverGradient = coverGradient

        val progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            id = R.id.progressBar
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.default_progress_bar)
            visibility = View.GONE
        }
        root.addView(progressBar)
        root.progressBar = progressBar

        val coverMetaOverlay = VideoCoverMetaOverlayView(context)
        root.addView(coverMetaOverlay)
        root.coverMetaOverlay = coverMetaOverlay

        val iconPlaying = ImageView(context).apply {
            id = R.id.icon_playing
            visibility = View.GONE
        }
        root.addView(iconPlaying)
        root.iconPlaying = iconPlaying

        val title = FastTitleTextView(context).apply {
            id = R.id.textView
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
            minLines = 2
            text = appName
            setTextColor(ContextCompat.getColor(context, R.color.textColor))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.titleTextSize)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        root.titleView = title

        val textOverflow = TextView(context).apply {
            id = R.id.text_overflow
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            maxLines = 1
            text = appName
            setTextColor(ContextCompat.getColor(context, R.color.textColor))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.titleTextSize)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        root.addView(textOverflow)
        root.textOverflow = textOverflow

        val ownerRow = VideoOwnerRowView(context).apply {
            id = R.id.textView_owner
            bind(ownerText = appName, showAvatar = true)
        }
        root.addView(ownerRow)
        root.ownerRow = ownerRow

        val iconHistoryDevice = ImageView(context).apply {
            id = R.id.icon_history_device
            setImageResource(R.drawable.ic_history_device_mobile)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.subTextColor))
            visibility = View.GONE
        }
        val textHistoryViewTime = TextView(context).apply {
            id = R.id.text_history_view_time
            maxLines = 1
            text = appName
            setTextColor(ContextCompat.getColor(context, R.color.subTextColor))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.ownerTextSize)
            visibility = View.GONE
        }
        root.addView(iconHistoryDevice)
        root.addView(textHistoryViewTime)
        root.iconHistoryDevice = iconHistoryDevice
        root.textHistoryViewTime = textHistoryViewTime

        return VideoCardViews(
            root = root,
            imageView = imageView,
            progressBar = progressBar,
            coverMetaOverlay = coverMetaOverlay,
            iconPlaying = iconPlaying,
            textView = title,
            textOverflow = textOverflow,
            ownerRow = ownerRow,
            iconHistoryDevice = iconHistoryDevice,
            textHistoryViewTime = textHistoryViewTime
        )
    }

}

class VideoCoverMetaOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val metrics = LightCardMetrics.get(context)
    private val assets = OverlayAssets.get(context, metrics.metaIconSize)
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.textColor)
        textSize = metrics.metaTextSize
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = metrics.badgeTextSize
    }
    private val badgeRect = RectF()

    private var playCountText: CharSequence = ""
    private var danmakuText: CharSequence = ""
    private var durationText: CharSequence = ""
    private var showPlayCount = false
    private var showDanmakuCount = false
    private var showInteractionBadge = false
    private var showChargeBadge = false

    fun bind(
        playCountText: CharSequence? = this.playCountText,
        showPlayCount: Boolean = this.showPlayCount,
        danmakuText: CharSequence? = this.danmakuText,
        showDanmakuCount: Boolean = this.showDanmakuCount,
        durationText: CharSequence? = this.durationText,
        showInteractionBadge: Boolean = this.showInteractionBadge,
        showChargeBadge: Boolean = this.showChargeBadge
    ) {
        val nextPlayCount = playCountText?.toString().orEmpty()
        val nextDanmaku = danmakuText?.toString().orEmpty()
        val nextDuration = durationText?.toString().orEmpty()
        if (TextUtils.equals(this.playCountText, nextPlayCount) &&
            TextUtils.equals(this.danmakuText, nextDanmaku) &&
            TextUtils.equals(this.durationText, nextDuration) &&
            this.showPlayCount == showPlayCount &&
            this.showDanmakuCount == showDanmakuCount &&
            this.showInteractionBadge == showInteractionBadge &&
            this.showChargeBadge == showChargeBadge
        ) {
            return
        }
        this.playCountText = nextPlayCount
        this.danmakuText = nextDanmaku
        this.durationText = nextDuration
        this.showPlayCount = showPlayCount
        this.showDanmakuCount = showDanmakuCount
        this.showInteractionBadge = showInteractionBadge
        this.showChargeBadge = showChargeBadge
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawMetaLine(canvas)
        drawDuration(canvas)
        drawBadges(canvas)
    }

    private fun drawMetaLine(canvas: Canvas) {
        var x = metrics.coverMetaStart.toFloat()
        val centerY = height - metrics.coverMetaBottom - metrics.metaIconSize / 2f
        val iconTop = centerY - metrics.metaIconSize / 2f
        if (showPlayCount && playCountText.isNotBlank()) {
            canvas.drawBitmap(assets.playCountIcon, x, iconTop, null)
            x += metrics.metaIconSize + metrics.metaTextStart
            x = drawMetaText(canvas, playCountText, x, centerY) + metrics.metaGroupGap
        }
        if (showDanmakuCount && danmakuText.isNotBlank()) {
            canvas.drawBitmap(assets.danmakuIcon, x, iconTop, null)
            x += metrics.metaIconSize + metrics.metaTextStart
            drawMetaText(canvas, danmakuText, x, centerY)
        }
    }

    private fun drawMetaText(canvas: Canvas, text: CharSequence, x: Float, centerY: Float): Float {
        val fm = metaPaint.fontMetrics
        val textHeight = ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px3
        val baseline = centerY - textHeight / 2f + metrics.px3 - fm.top
        canvas.drawText(text.toString(), x, baseline, metaPaint)
        return x + metaPaint.measureText(text.toString())
    }

    private fun drawDuration(canvas: Canvas) {
        if (durationText.isBlank()) return
        val text = durationText.toString()
        val fm = metaPaint.fontMetrics
        val textHeight = ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px4 * 2
        val textWidth = metaPaint.measureText(text)
        val boxWidth = textWidth + metrics.px8 * 2
        val left = width - metrics.durationEnd - boxWidth
        val top = height - metrics.durationBottom - textHeight
        val baseline = top + metrics.px4 - fm.top
        canvas.drawText(text, left + metrics.px8, baseline, metaPaint)
    }

    private fun drawBadges(canvas: Canvas) {
        var right = width - metrics.badgeEnd.toFloat()
        val top = metrics.badgeTop.toFloat()
        if (showChargeBadge) {
            right = drawBadge(canvas, "充电专属", right, top, 0xFFF6A11B.toInt()) - metrics.badgeGap
        }
        if (showInteractionBadge) {
            drawBadge(canvas, "互动", right, top, 0xFFFB7299.toInt())
        }
    }

    private fun drawBadge(canvas: Canvas, text: String, right: Float, top: Float, color: Int): Float {
        val fm = badgeTextPaint.fontMetrics
        val textWidth = badgeTextPaint.measureText(text)
        val badgeWidth = textWidth + metrics.px8 * 2
        val badgeHeight = ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px3 * 2
        val left = right - badgeWidth
        badgeRect.set(left, top, right, top + badgeHeight)
        badgePaint.color = color
        canvas.drawRoundRect(badgeRect, metrics.badgeRadius.toFloat(), metrics.badgeRadius.toFloat(), badgePaint)
        canvas.drawText(text, left + metrics.px8, top + metrics.px3 - fm.top, badgeTextPaint)
        return left
    }

    private class OverlayAssets(
        val playCountIcon: Bitmap,
        val danmakuIcon: Bitmap
    ) {
        companion object {
            private val cache = java.util.WeakHashMap<android.content.res.Resources, MutableMap<Int, OverlayAssets>>()

            fun get(context: Context, iconSize: Int): OverlayAssets {
                val resources = context.resources
                synchronized(cache) {
                    val bySize = cache.getOrPut(resources) { mutableMapOf() }
                    return bySize.getOrPut(iconSize) {
                        OverlayAssets(
                            playCountIcon = loadBitmap(context, R.drawable.ic_video_play_count, iconSize),
                            danmakuIcon = loadBitmap(context, R.drawable.ic_video_danmaku, iconSize)
                        )
                    }
                }
            }

            private fun loadBitmap(context: Context, resId: Int, size: Int): Bitmap {
                val drawable = ContextCompat.getDrawable(context, resId)
                    ?: return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                return drawable.toBitmap(size, size)
            }

            private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                setBounds(0, 0, width, height)
                draw(canvas)
                return bitmap
            }
        }
    }
}

class VideoOwnerRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val metrics = LightCardMetrics.get(context)
    private val ownerTextColor = ContextCompat.getColor(context, R.color.subTextColor)
    private val assets = OwnerAssets.get(context, metrics, ownerTextColor)
    private val ownerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ownerTextColor
        textSize = metrics.ownerTextSize
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF6A11B.toInt()
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = metrics.badgeTextSize
        typeface = Typeface.DEFAULT_BOLD
    }
    private val badgeRect = RectF()

    private var ownerText: String = ""
    private var badgeText: String = ""
    private var showAvatar = false

    fun bind(
        ownerText: CharSequence?,
        showAvatar: Boolean,
        badgeText: CharSequence? = null,
        show: Boolean = true
    ) {
        val nextOwner = ownerText?.toString().orEmpty()
        val nextBadge = badgeText?.toString().orEmpty()
        val hasContent = show && (nextOwner.isNotBlank() || nextBadge.isNotBlank() || showAvatar)
        val nextVisibility = if (hasContent) VISIBLE else GONE
        val layoutMayChange = this.showAvatar != showAvatar ||
            this.badgeText.isBlank() != nextBadge.isBlank() ||
            visibility != nextVisibility

        if (this.ownerText == nextOwner &&
            this.badgeText == nextBadge &&
            this.showAvatar == showAvatar &&
            visibility == nextVisibility
        ) {
            return
        }

        this.ownerText = nextOwner
        this.badgeText = nextBadge
        this.showAvatar = showAvatar
        visibility = nextVisibility
        if (layoutMayChange) {
            requestLayout()
        } else {
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (visibility == GONE) {
            setMeasuredDimension(0, 0)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = maxOf(
            if (showAvatar && badgeText.isBlank()) metrics.avatarHeight else 0,
            if (badgeText.isNotBlank()) badgeHeight().toInt() else 0,
            if (ownerText.isNotBlank()) ownerTextHeight().toInt() else 0
        )
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility == GONE) return

        var x = metrics.avatarStart.toFloat()
        val centerY = height / 2f
        if (badgeText.isNotBlank()) {
            x = drawLeadingBadge(canvas, x, centerY) + metrics.badgeOwnerGap
        } else if (showAvatar) {
            val top = centerY - assets.ownerIcon.height / 2f
            canvas.drawBitmap(assets.ownerIcon, x, top, null)
            x += metrics.avatarWidth
        }

        if (ownerText.isNotBlank()) {
            val availableWidth = (width - x).coerceAtLeast(0f)
            if (availableWidth > 0f) {
                val text = TextUtils.ellipsize(ownerText, ownerPaint, availableWidth, TextUtils.TruncateAt.END).toString()
                val fm = ownerPaint.fontMetrics
                val baseline = centerY - ownerTextHeight() / 2f - fm.top
                canvas.drawText(text, x, baseline, ownerPaint)
            }
        }
    }

    private fun drawLeadingBadge(canvas: Canvas, left: Float, centerY: Float): Float {
        val width = badgeTextPaint.measureText(badgeText) + metrics.px8 * 2
        val height = badgeHeight()
        val top = centerY - height / 2f
        badgeRect.set(left, top, left + width, top + height)
        canvas.drawRoundRect(badgeRect, metrics.badgeRadius.toFloat(), metrics.badgeRadius.toFloat(), badgePaint)
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(badgeText, left + metrics.px8, top + metrics.px3 - fm.top, badgeTextPaint)
        return left + width
    }

    private fun ownerTextHeight(): Float {
        val fm = ownerPaint.fontMetrics
        return ceil((fm.bottom - fm.top).toDouble()).toFloat()
    }

    private fun badgeHeight(): Float {
        val fm = badgeTextPaint.fontMetrics
        return ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px3 * 2
    }

    private class OwnerAssets(val ownerIcon: Bitmap) {
        companion object {
            private val cache = java.util.WeakHashMap<android.content.res.Resources, MutableMap<String, OwnerAssets>>()

            fun get(context: Context, metrics: LightCardMetrics, tintColor: Int): OwnerAssets {
                val resources = context.resources
                val key = "${metrics.avatarWidth}:${metrics.avatarHeight}:$tintColor"
                synchronized(cache) {
                    val byKey = cache.getOrPut(resources) { mutableMapOf() }
                    return byKey.getOrPut(key) {
                        OwnerAssets(
                            ownerIcon = loadTintedBitmap(
                                context = context,
                                resId = R.drawable.ic_video_up,
                                width = metrics.avatarWidth - metrics.px10,
                                height = metrics.avatarHeight,
                                tintColor = tintColor
                            )
                        )
                    }
                }
            }

            private fun loadTintedBitmap(
                context: Context,
                resId: Int,
                width: Int,
                height: Int,
                tintColor: Int
            ): Bitmap {
                val drawable = ContextCompat.getDrawable(context, resId)?.mutate()
                    ?: return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawable.setTint(tintColor)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                return bitmap
            }
        }
    }
}

private class FlatVideoLightCardLayout @JvmOverloads constructor(
    context: Context,
    private val metrics: LightCardMetrics = LightCardMetrics.get(context),
    private val perfSource: String = "VideoLightCard.flat",
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var coverGradient: View? = null
    lateinit var imageView: View
    lateinit var progressBar: View
    lateinit var coverMetaOverlay: View
    lateinit var iconPlaying: View
    lateinit var titleView: View
    lateinit var textOverflow: View
    lateinit var ownerRow: View
    lateinit var iconHistoryDevice: View
    lateinit var textHistoryViewTime: View

    private var coverLeft = 0
    private var coverTop = 0
    private var coverWidth = 0
    private var coverHeight = 0
    private var titleTop = 0
    private var titleRowHeight = 0
    private var overflowTop = 0
    private var ownerTop = 0
    private var ownerRowHeight = 0
    private var lastContentWidth = -1
    private var lastDesiredHeight = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (widthSize - metrics.horizontalMargin * 2).coerceAtLeast(0)
        if (canReuseMeasuredChildren(widthSize, contentWidth)) {
            setMeasuredDimension(
                resolveSize(widthSize, widthMeasureSpec),
                resolveSize(lastDesiredHeight, heightMeasureSpec)
            )
            VideoCardPerfLogger.recordPhase(perfSource, "measure_reuse", SystemClock.elapsedRealtimeNanos() - startNs)
            return
        }
        coverWidth = contentWidth
        coverHeight = contentWidth * 9 / 16

        measureExact(imageView, coverWidth, coverHeight)
        coverGradient?.let { measureExact(it, coverWidth, metrics.coverGradientHeight) }
        measureExact(progressBar, coverWidth, metrics.progressHeight)
        measureExact(coverMetaOverlay, coverWidth, coverHeight)

        val playingIconWidth = if (iconPlaying.visibility != GONE) {
            val lp = iconPlaying.layoutParams
            val size = when {
                lp.width > 0 -> lp.width
                lp.height > 0 -> lp.height
                else -> metrics.titleIconFallbackSize
            }
            measureExact(iconPlaying, size, size)
            size + metrics.playingIconMarginEnd
        } else {
            measureExact(iconPlaying, 0, 0)
            0
        }
        measureExactWidth(titleView, (contentWidth - playingIconWidth).coerceAtLeast(0))
        titleRowHeight = maxOf(titleView.measuredHeight, visibleHeight(iconPlaying))

        if (textOverflow.visibility != GONE) {
            measureExactWidth(textOverflow, contentWidth)
        } else {
            measureExact(textOverflow, 0, 0)
        }

        measureExact(iconHistoryDevice, metrics.historyDeviceSize, metrics.historyDeviceSize)
        measureWrap(textHistoryViewTime, contentWidth)
        val ownerTrailingWidth = ownerTrailingWidth()
        measureExactWidth(ownerRow, (contentWidth - ownerTrailingWidth - metrics.ownerTextMarginEnd).coerceAtLeast(0))
        ownerRowHeight = maxOf(
            ownerRow.measuredHeight,
            visibleHeight(iconHistoryDevice),
            visibleHeight(textHistoryViewTime)
        )

        coverLeft = metrics.horizontalMargin
        coverTop = metrics.paddingTop
        titleTop = coverTop + coverHeight + metrics.titleTopMargin
        overflowTop = titleTop + titleRowHeight
        ownerTop = overflowTop + visibleHeight(textOverflow) + metrics.ownerTopMargin

        val desiredHeight = ownerTop + ownerRowHeight + metrics.paddingBottom
        lastContentWidth = contentWidth
        lastDesiredHeight = desiredHeight
        setMeasuredDimension(
            resolveSize(widthSize, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
        VideoCardPerfLogger.recordPhase(perfSource, "measure", SystemClock.elapsedRealtimeNanos() - startNs)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val coverRight = coverLeft + coverWidth
        val coverBottom = coverTop + coverHeight

        imageView.layout(coverLeft, coverTop, coverRight, coverBottom)
        coverGradient?.layout(
            coverLeft,
            coverBottom - metrics.coverGradientHeight,
            coverRight,
            coverBottom
        )
        progressBar.layout(coverLeft, coverBottom - metrics.progressHeight, coverRight, coverBottom)
        coverMetaOverlay.layout(coverLeft, coverTop, coverRight, coverBottom)

        var titleLeft = coverLeft
        if (iconPlaying.visibility != GONE) {
            val iconTop = titleTop + (titleRowHeight - iconPlaying.measuredHeight) / 2
            layoutAt(iconPlaying, titleLeft, iconTop)
            titleLeft += iconPlaying.measuredWidth + metrics.playingIconMarginEnd
        }
        layoutAt(titleView, titleLeft, titleTop + (titleRowHeight - titleView.measuredHeight) / 2)
        if (textOverflow.visibility != GONE) {
            layoutAt(textOverflow, coverLeft, overflowTop)
        }

        var ownerRight = coverRight
        if (textHistoryViewTime.visibility != GONE) {
            ownerRight -= textHistoryViewTime.measuredWidth
            layoutAt(textHistoryViewTime, ownerRight, ownerTop + (ownerRowHeight - textHistoryViewTime.measuredHeight) / 2)
        }
        if (iconHistoryDevice.visibility != GONE) {
            ownerRight -= metrics.historyDeviceMarginEnd
            ownerRight -= iconHistoryDevice.measuredWidth
            layoutAt(iconHistoryDevice, ownerRight, ownerTop + (ownerRowHeight - iconHistoryDevice.measuredHeight) / 2)
        }
        if (ownerRow.visibility != GONE) {
            val ownerRowRight = (ownerRight - metrics.ownerTextMarginEnd).coerceAtLeast(coverLeft)
            ownerRow.layout(
                coverLeft,
                ownerTop + (ownerRowHeight - ownerRow.measuredHeight) / 2,
                ownerRowRight,
                ownerTop + (ownerRowHeight + ownerRow.measuredHeight) / 2
            )
        }
        VideoCardPerfLogger.recordPhase(perfSource, "layout", SystemClock.elapsedRealtimeNanos() - startNs)
    }

    private fun ownerTrailingWidth(): Int {
        var width = 0
        if (textHistoryViewTime.visibility != GONE) {
            width += textHistoryViewTime.measuredWidth
        }
        if (iconHistoryDevice.visibility != GONE) {
            width += iconHistoryDevice.measuredWidth + metrics.historyDeviceMarginEnd
        }
        return width
    }

    private fun visibleHeight(view: View): Int = if (view.visibility == GONE) 0 else view.measuredHeight

    private fun canReuseMeasuredChildren(widthSize: Int, contentWidth: Int): Boolean {
        if (widthSize <= 0 || lastContentWidth != contentWidth || lastDesiredHeight <= 0) {
            return false
        }
        return !coverMetaOverlay.isLayoutRequested &&
            !iconPlaying.isLayoutRequested &&
            !titleView.isLayoutRequested &&
            !textOverflow.isLayoutRequested &&
            !ownerRow.isLayoutRequested &&
            !iconHistoryDevice.isLayoutRequested &&
            !textHistoryViewTime.isLayoutRequested
    }

    private fun measureExact(child: View, width: Int, height: Int) {
        child.measure(exact(width), exact(height))
    }

    private fun measureExactWidth(child: View, width: Int) {
        if (child.visibility == GONE) {
            measureExact(child, 0, 0)
            return
        }
        child.measure(exact(width), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    private fun measureWrap(child: View, width: Int) {
        if (child.visibility == GONE) {
            measureExact(child, 0, 0)
            return
        }
        child.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    private fun layoutAt(child: View, left: Int, top: Int) {
        if (child.visibility == GONE) return
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: ViewGroup.LayoutParams?): LayoutParams =
        LayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams?): Boolean =
        params is LayoutParams

    private fun exact(size: Int): Int = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
}

private class LightCardMetrics private constructor(context: Context) {
    val paddingTop = dimen(context, R.dimen.px20)
    val paddingBottom = dimen(context, R.dimen.px15)
    val horizontalMargin = dimen(context, R.dimen.px15)
    val titleTopMargin = dimen(context, R.dimen.px10)
    val ownerTopMargin = dimen(context, R.dimen.px5)
    val avatarStart = dimen(context, R.dimen.px2)
    val ownerTextMarginEnd = dimen(context, R.dimen.px8)
    val coverGradientHeight = dimen(context, R.dimen.px70)
    val progressHeight = dimen(context, R.dimen.px3)
    val metaIconSize = dimen(context, R.dimen.px30)
    val coverMetaStart = dimen(context, R.dimen.px10)
    val coverMetaBottom = dimen(context, R.dimen.px10)
    val metaTextStart = dimen(context, R.dimen.px5)
    val metaGroupGap = dimen(context, R.dimen.px10)
    val durationEnd = dimen(context, R.dimen.px20)
    val durationBottom = dimen(context, R.dimen.px10)
    val badgeTop = dimen(context, R.dimen.px10)
    val badgeEnd = dimen(context, R.dimen.px10)
    val badgeGap = dimen(context, R.dimen.px10)
    val badgeRadius = dimen(context, R.dimen.px5)
    val px3 = dimen(context, R.dimen.px3)
    val px4 = dimen(context, R.dimen.px4)
    val px8 = dimen(context, R.dimen.px8)
    val px10 = dimen(context, R.dimen.px10)
    val avatarWidth = dimen(context, R.dimen.px40)
    val avatarHeight = dimen(context, R.dimen.px25)
    val badgeOwnerGap = dimen(context, R.dimen.px6)
    val historyDeviceSize = dimen(context, R.dimen.px30)
    val historyDeviceMarginEnd = dimen(context, R.dimen.px5)
    val playingIconMarginEnd = dp(context, 4)
    val titleIconFallbackSize = dimen(context, R.dimen.px31)
    val titleTextSize = dimenF(context, R.dimen.px31)
    val ownerTextSize = dimenF(context, R.dimen.px22)
    val metaTextSize = dimenF(context, R.dimen.px22)
    val badgeTextSize = dimenF(context, R.dimen.px20)

    private fun dimen(context: Context, resId: Int): Int = context.resources.getDimensionPixelSize(resId)

    private fun dimenF(context: Context, resId: Int): Float = context.resources.getDimension(resId)

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    companion object {
        private val cache = java.util.WeakHashMap<android.content.res.Resources, LightCardMetrics>()

        fun get(context: Context): LightCardMetrics {
            val resources = context.resources
            synchronized(cache) {
                return cache.getOrPut(resources) { LightCardMetrics(context.applicationContext ?: context) }
            }
        }
    }
}
