package com.btelly.core.ui.video

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil

class FastTitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var cachedLines: List<String> = emptyList()
    private var cachedText: CharSequence? = null
    private var cachedWidth = -1
    private var cachedMaxLines = -1
    private var cachedTextSize = -1f
    private var cachedColor = 0
    private var cachedTypeface = typeface
    private var drawTextColor = currentTextColor

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        val lineCount = maxLines.takeIf { it > 0 } ?: 1
        val desiredHeight = lineHeightPx() * lineCount + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val drawWidth = width - paddingLeft - paddingRight
        if (drawWidth <= 0 || text.isNullOrEmpty()) return
        val lines = ensureLines(drawWidth)
        if (lines.isEmpty()) return
        val fontMetrics = paint.fontMetrics
        val lineHeight = lineHeightPx()
        var baseline = paddingTop - fontMetrics.ascent

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), 0f)
        for (line in lines) {
            canvas.drawText(line, 0f, baseline, paint)
            baseline += lineHeight
        }
        canvas.restore()
    }

    fun firstLineVisibleEnd(source: CharSequence = text ?: ""): Int {
        val drawWidth = width - paddingLeft - paddingRight
        if (drawWidth <= 0 || source.isEmpty()) return source.length
        return visibleEndForWidth(source, 0, source.length, drawWidth, reserveEllipsis = true)
    }

    private fun ensureLines(width: Int): List<String> {
        val currentText = text ?: ""
        val currentMaxLines = maxLines.takeIf { it > 0 } ?: 1
        val textPaint = paint as TextPaint
        val currentTypeface = typeface
        if (
            cachedWidth == width &&
            cachedText == currentText &&
            cachedMaxLines == currentMaxLines &&
            cachedTextSize == textPaint.textSize &&
            cachedColor == drawTextColor &&
            cachedTypeface == currentTypeface
        ) {
            return cachedLines
        }

        val newLines = buildLines(currentText, width, currentMaxLines)
        cachedLines = newLines
        cachedText = currentText
        cachedWidth = width
        cachedMaxLines = currentMaxLines
        cachedTextSize = textPaint.textSize
        cachedColor = drawTextColor
        cachedTypeface = currentTypeface
        return newLines
    }

    private fun buildLines(source: CharSequence, width: Int, lineLimit: Int): List<String> {
        if (source.isEmpty() || lineLimit <= 0) return emptyList()
        val lines = ArrayList<String>(lineLimit)
        var start = 0
        val end = source.length
        while (start < end && lines.size < lineLimit) {
            while (start < end && source[start] == '\n') start++
            if (start >= end) break

            val lastLine = lines.size == lineLimit - 1
            if (lastLine) {
                lines += TextUtils.ellipsize(
                    source.subSequence(start, end),
                    paint,
                    width.toFloat(),
                    TextUtils.TruncateAt.END
                ).toString()
                break
            }

            val lineEnd = visibleEndForWidth(source, start, end, width, reserveEllipsis = false)
            val newline = indexOfNewline(source, start, lineEnd)
            val actualEnd = if (newline >= 0) newline else lineEnd
            if (actualEnd <= start) {
                lines += source.subSequence(start, (start + 1).coerceAtMost(end)).toString()
                start = (start + 1).coerceAtMost(end)
            } else {
                lines += source.subSequence(start, actualEnd).trimEnd().toString()
                start = if (newline >= 0) actualEnd + 1 else actualEnd
            }
            while (start < end && source[start].isWhitespace() && source[start] != '\n') start++
        }
        return lines
    }

    private fun visibleEndForWidth(
        source: CharSequence,
        start: Int,
        end: Int,
        width: Int,
        reserveEllipsis: Boolean
    ): Int {
        if (start >= end) return end
        val availableWidth = if (reserveEllipsis) {
            (width - paint.measureText(ELLIPSIS)).coerceAtLeast(0f)
        } else {
            width.toFloat()
        }
        val count = paint.breakText(source, start, end, true, availableWidth, null)
        return (start + count).coerceIn(start + 1, end)
    }

    private fun indexOfNewline(source: CharSequence, start: Int, end: Int): Int {
        for (i in start until end) {
            if (source[i] == '\n') return i
        }
        return -1
    }

    private fun lineHeightPx(): Int {
        val fontMetrics = paint.fontMetrics
        return ceil(fontMetrics.descent - fontMetrics.ascent).toInt().coerceAtLeast(1)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (TextUtils.equals(this.text, text)) return
        clearLineCache()
        super.setText(text, type)
    }

    override fun setTextColor(color: Int) {
        clearLineCache()
        super.setTextColor(color)
        drawTextColor = color
        paint.color = color
        invalidate()
    }

    override fun setTextColor(colors: ColorStateList?) {
        clearLineCache()
        super.setTextColor(colors)
        val color = colors?.getColorForState(drawableState, colors.defaultColor) ?: currentTextColor
        drawTextColor = color
        paint.color = color
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val color = textColors?.getColorForState(drawableState, currentTextColor) ?: currentTextColor
        if (drawTextColor != color) {
            drawTextColor = color
            paint.color = color
            clearLineCache()
            invalidate()
        }
    }

    override fun setTypeface(tf: android.graphics.Typeface?) {
        clearLineCache()
        super.setTypeface(tf)
    }

    private fun clearLineCache() {
        cachedLines = emptyList()
        cachedText = null
        cachedWidth = -1
    }

    private companion object {
        private const val ELLIPSIS = "\u2026"
    }
}
