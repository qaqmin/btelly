package com.btelly.feature.player.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import com.btelly.model.dm.DmMaskTimeline
import com.btelly.model.dm.MaskFrame

class DanmakuMaskHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var timeline: DmMaskTimeline? = null
    var ptsProvider: (() -> Long)? = null
    var videoBoundsProvider: (() -> Rect)? = null

    private var pathMergeThreshold: Int = 20

    private val transformMatrix = Matrix()
    private val transformPath = Path()
    private val mergedPath = Path()

    override fun dispatchDraw(canvas: Canvas) {
        val tl = timeline
        val pts = ptsProvider?.invoke()
        val bounds = videoBoundsProvider?.invoke()
        val frame = if (tl != null && pts != null) tl.queryAt(pts) else null

        if (frame == null || bounds == null || bounds.isEmpty || frame.paths.isEmpty()) {
            super.dispatchDraw(canvas)
            return
        }

        val sx = bounds.width().toFloat() / frame.svgWidth.coerceAtLeast(1)
        val sy = bounds.height().toFloat() / frame.svgHeight.coerceAtLeast(1)
        val dx = bounds.left.toFloat()
        val dy = bounds.top.toFloat()

        // 将 SVG 坐标系的 path 变换到 maskHost 坐标系，然后直接 clip
        // 不能用 save/translate/scale/clip/restore —— restore 会把 clip 一起还原
        transformMatrix.setScale(sx, sy)
        transformMatrix.postTranslate(dx, dy)

        clipOutTransformedPaths(canvas, frame)
        super.dispatchDraw(canvas)
    }

    private fun clipOutTransformedPaths(canvas: Canvas, frame: MaskFrame) {
        // mask path 表示"弹幕可绘制区域"（背景/非人物区），用 clipPath 保留这些区域，
        // 人物区域（无 path 覆盖）自然被裁掉，弹幕画不到人物上。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (frame.paths.size >= pathMergeThreshold) {
                mergedPath.reset()
                for (path in frame.paths) {
                    transformPath.set(path)
                    transformPath.transform(transformMatrix)
                    mergedPath.addPath(transformPath)
                }
                canvas.clipPath(mergedPath)
            } else {
                for (path in frame.paths) {
                    transformPath.set(path)
                    transformPath.transform(transformMatrix)
                    canvas.clipPath(transformPath)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (frame.paths.size >= pathMergeThreshold) {
                mergedPath.reset()
                for (path in frame.paths) {
                    transformPath.set(path)
                    transformPath.transform(transformMatrix)
                    mergedPath.addPath(transformPath)
                }
                canvas.clipPath(mergedPath, Region.Op.INTERSECT)
            } else {
                for (path in frame.paths) {
                    transformPath.set(path)
                    transformPath.transform(transformMatrix)
                    canvas.clipPath(transformPath, Region.Op.INTERSECT)
                }
            }
        }
    }
}
