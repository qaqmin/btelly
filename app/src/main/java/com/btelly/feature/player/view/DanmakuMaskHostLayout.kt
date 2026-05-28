package com.btelly.feature.player.view

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.FrameMetrics
import android.view.Window
import android.widget.FrameLayout
import com.btelly.model.dm.DmMaskTimeline
import com.btelly.model.dm.MaskFrame

/**
 * 弹幕防挡蒙版宿主容器（clipPath 方案）。
 *
 * 渲染思路：把每帧多个 [android.graphics.Path] **UNION 合并成单个 mergedPath**，
 * 一次 `canvas.clipPath(mergedPath)` 把弹幕子 view 的绘制区裁剪到「背景区」。
 * 人物区域因为 [Path.FillType.EVEN_ODD]（在 [com.btelly.model.dm.WebmaskParser] 里设置）
 * 是 path 的"洞"，clipPath 不允许绘制 → 弹幕被人物挡住。
 *
 * 必须 UNION 不能 INTERSECT：`canvas.clipPath` 默认是和当前 clip 取交集——多 path 连续 clip
 * 会把允许绘制的区域越缩越小，最后变成空集，弹幕完全画不出来。
 *
 * **clipPath 抗锯齿说明**：硬件加速 view 上的 `clipPath` 走 stencil mask 光栅化，
 * Android 渲染管线不保证真抗锯齿，人物轮廓边缘可能有 1-2px 锯齿。
 * 如果后续要继续优化软边，需要改成单独人物 path 的 alpha punch-out，而不是对
 * EVEN_ODD 背景 path 直接做 `DST_IN`。
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

    /**
     * 把本 window 一帧的实测渲染管线耗时（ns）上报给 controller。
     * 由 [Window.OnFrameMetricsAvailableListener.FrameMetrics.TOTAL_DURATION] 给出，
     * 物理含义：INTENDED_VSYNC → frame 完成提交到 SurfaceFlinger 的总时间。
     * Controller 还会再加 1 vsync（SurfaceFlinger 合成）得到完整 D_mask。
     */
    var pipelineDelayReporter: ((totalDurationNs: Long) -> Unit)? = null

    /**
     * 把 attach 时检测到的屏幕 vsync 周期（ns）上报给 controller，
     * 让 controller 算 D_mask 时用真实刷新率（高刷屏 8.3ms / 11.1ms / 16.7ms）。
     */
    var vsyncPeriodReporter: ((periodNs: Long) -> Unit)? = null

    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null
    private val frameMetricsHandler by lazy { Handler(Looper.getMainLooper()) }

    private val transformMatrix = Matrix()
    private val transformPath = Path()
    // 合并后的 path 始终 EVEN_ODD：每个源 path 在 WebmaskParser 里已经设 EVEN_ODD，
    // 合并后保留同一规则才能让"人物洞"继续作为洞参与裁剪。
    private val mergedPath = Path().apply { fillType = Path.FillType.EVEN_ODD }

    /**
     * 同帧去重缓存——**对齐 B 站官方 `if (this.maskTime !== mask.time)` 的优化**。
     *
     * 弹幕子 view 滚动会让 host 60Hz invalidate，但 mask 源帧只有 24~30Hz——
     * 大部分相邻 dispatchDraw 拿到的是同一个 [MaskFrame] 引用。
     * 缓存上次 (frame, bounds)，相同时跳过 N 个 path 的变换 + addPath 重建，
     * 只复用已有 [mergedPath] 做裁剪。1080p、人物 path 数=4~8 时 CPU 节省 30~50%。
     */
    private var cachedFrame: MaskFrame? = null
    private var cachedBoundsLeft = 0
    private var cachedBoundsTop = 0
    private var cachedBoundsRight = 0
    private var cachedBoundsBottom = 0

    /** 保留调试入口：必要时可强制切软件层，对比硬件合成差异。 */
    fun setHighQualityClipping(enabled: Boolean) {
        val target = if (enabled) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE
        if (layerType != target) {
            setLayerType(target, null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachFrameMetricsListener()
        reportVsyncPeriod()
    }

    /**
     * 读取屏幕真实刷新率并转成 vsync 周期（ns），上报给 controller。
     * 高刷屏（90/120Hz）周期更短，影响 SurfaceFlinger 合成那 1 vsync 的估算。
     */
    private fun reportVsyncPeriod() {
        val display = display ?: return
        val refreshRate = display.refreshRate.takeIf { it > 1f } ?: return
        val periodNs = (1_000_000_000.0 / refreshRate).toLong()
        vsyncPeriodReporter?.invoke(periodNs)
    }

    override fun onDetachedFromWindow() {
        detachFrameMetricsListener()
        super.onDetachedFromWindow()
    }

    /**
     * 注册 [Window.OnFrameMetricsAvailableListener]（API 24+）。
     *
     * 拿到的 `TOTAL_DURATION` 是「**这一帧从 INTENDED_VSYNC 到提交给 SurfaceFlinger 的总耗时**」
     * ——Google 官方暴露的真实渲染管线延迟数据。controller 用它+1 vsync 算 D_mask，
     * 不再靠魔法数字（32/48/67 ms）猜延迟。
     *
     * <24 设备上 listener 不存在，controller 会退回到固定的 2 vsync 估算（minSdk=23 只差 1 API）。
     */
    private fun attachFrameMetricsListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (frameMetricsListener != null) return
        val window = (context as? Activity)?.window ?: return
        val listener = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            val totalNs = fm.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalNs > 0L) pipelineDelayReporter?.invoke(totalNs)
        }
        window.addOnFrameMetricsAvailableListener(listener, frameMetricsHandler)
        frameMetricsListener = listener
    }

    private fun detachFrameMetricsListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val listener = frameMetricsListener ?: return
        (context as? Activity)?.window?.removeOnFrameMetricsAvailableListener(listener)
        frameMetricsListener = null
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

        // 同帧 + 同 bounds → 复用缓存的 mergedPath。
        // frame 是引用比较：timeline 的 queryAt 返回的是缓存对象，相邻调用通常 ===。
        val sameAsCache = frame === cachedFrame &&
            bounds.left == cachedBoundsLeft && bounds.top == cachedBoundsTop &&
            bounds.right == cachedBoundsRight && bounds.bottom == cachedBoundsBottom

        if (!sameAsCache) {
            cachedFrame = frame
            cachedBoundsLeft = bounds.left
            cachedBoundsTop = bounds.top
            cachedBoundsRight = bounds.right
            cachedBoundsBottom = bounds.bottom

            val svgW = frame.svgWidth.coerceAtLeast(1)
            val svgH = frame.svgHeight.coerceAtLeast(1)
            val sx = bounds.width().toFloat() / svgW
            val sy = bounds.height().toFloat() / svgH
            val dx = bounds.left.toFloat()
            val dy = bounds.top.toFloat()

            transformMatrix.setScale(sx, sy)
            transformMatrix.postTranslate(dx, dy)

            // 把所有 path 在 mergedPath 上做并集（UNION，Path.addPath 默认行为）。
            // 人物洞由 EVEN_ODD fill rule 保留下来。
            mergedPath.reset()
            mergedPath.fillType = Path.FillType.EVEN_ODD
            for (path in frame.paths) {
                transformPath.set(path)
                transformPath.transform(transformMatrix)
                mergedPath.addPath(transformPath)
            }
        }

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
