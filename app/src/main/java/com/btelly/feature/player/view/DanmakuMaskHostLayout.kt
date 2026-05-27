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

/**
 * 弹幕防挡蒙版宿主容器（clipPath 方案）。
 *
 * 渲染思路：把每帧多个 [android.graphics.Path] **UNION 合并成单个 mergedPath**，
 * 一次 `canvas.clipPath(mergedPath)` 把弹幕子 view 的绘制区裁剪到「背景区」，
 * 人物区域因为 [Path.FillType.EVEN_ODD]（在 [com.btelly.model.dm.WebmaskParser] 里设置）
 * 是 path 的"洞"，clipPath 不允许绘制 → 弹幕被人物挡住。
 *
 * 必须 UNION 不能 INTERSECT：`canvas.clipPath` 默认是和当前 clip 取交集——多 path 连续 clip
 * 会把允许绘制的区域越缩越小，最后变成空集，弹幕完全画不出来。
 *
 * **clipPath 抗锯齿说明**：硬件加速 view 上的 `clipPath` 走 stencil mask 光栅化，
 * **Android 渲染管线物理上不支持抗锯齿**，1080p 上人物轮廓边缘可能有 1-2px 锯齿。
 * 多数场景（人物边缘本身是渐变软边）肉眼难察觉；若审美要求极致，可调用
 * [setHighQualityClipping] 切到软件渲染获得真 AA——但代价是弹幕子 view 也走软件渲染，
 * 帧率会下降，**仅推荐截图/审查使用，不建议生产开启**。
 */
class DanmakuMaskHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var timeline: DmMaskTimeline? = null
    var ptsProvider: (() -> Long)? = null
    var videoBoundsProvider: (() -> Rect)? = null

    /**
     * 由 controller 注入：seek 等待 video 首帧 / mask 数据未 ready 等场景返回 false，
     * 此时直接走 super.dispatchDraw 不做任何裁剪，避免「mask 跳到新位置而视频还停在旧位置」的错位。
     */
    var shouldRenderMask: (() -> Boolean)? = null

    /**
     * 把"这次 dispatchDraw 查到的 (queryPts, framePts) 对"反馈给 controller，
     * controller 节流后输出统一的同步诊断日志。
     */
    var frameQueryReporter: ((queryPtsMs: Long, framePtsMs: Long) -> Unit)? = null

    private val transformMatrix = Matrix()
    private val transformPath = Path()
    // 合并后的 path 始终 EVEN_ODD：每个源 path 在 WebmaskParser 里已经设 EVEN_ODD，
    // 合并后保留同一规则才能让"人物洞"继续作为洞参与裁剪。
    private val mergedPath = Path().apply { fillType = Path.FillType.EVEN_ODD }

    /**
     * 切换到软件渲染层以获得 clipPath 的真抗锯齿。**代价**：整个 view 子树（弹幕在内）
     * 都走软件渲染，文字 GPU 加速失效，弹幕滚动会卡。仅供截图 / 审查阶段使用。
     *
     * 默认 false——生产场景容忍 1-2px clipPath 锯齿换 GPU 加速性能。
     */
    fun setHighQualityClipping(enabled: Boolean) {
        val target = if (enabled) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE
        if (layerType != target) {
            setLayerType(target, null)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (shouldRenderMask?.invoke() == false) {
            super.dispatchDraw(canvas)
            return
        }
        val tl = timeline
        val pts = ptsProvider?.invoke()
        val bounds = videoBoundsProvider?.invoke()
        val frame = if (tl != null && pts != null) tl.queryAt(pts) else null

        if (frame != null && pts != null) {
            // 把这一帧的 (queryPts, framePts) 上报给 controller 做集中诊断输出。
            // controller 内部已经节流（每秒最多 1 条），这里只是塞数据，开销忽略不计。
            frameQueryReporter?.invoke(pts, frame.presentationTimeMs)
        }

        if (frame == null || bounds == null || bounds.isEmpty || frame.paths.isEmpty()) {
            super.dispatchDraw(canvas)
            return
        }

        val svgW = frame.svgWidth.coerceAtLeast(1)
        val svgH = frame.svgHeight.coerceAtLeast(1)
        val sx = bounds.width().toFloat() / svgW
        val sy = bounds.height().toFloat() / svgH
        val dx = bounds.left.toFloat()
        val dy = bounds.top.toFloat()

        transformMatrix.setScale(sx, sy)
        transformMatrix.postTranslate(dx, dy)

        // 1. 把所有 path 在 mergedPath 上做并集（UNION，Path.addPath 默认行为）。
        //    人物洞由 EVEN_ODD fill rule 保留下来。
        mergedPath.reset()
        for (path in frame.paths) {
            transformPath.set(path)
            transformPath.transform(transformMatrix)
            mergedPath.addPath(transformPath)
        }

        // 2. 一次 clipPath 把绘制区限制到「画面减人物」的范围。
        val saveCount = canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipPath(mergedPath)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(mergedPath, Region.Op.INTERSECT)
        }
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveCount)
    }
}
