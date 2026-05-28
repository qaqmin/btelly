package com.btelly.model.dm

import android.graphics.Path
import android.util.Base64
import com.btelly.core.common.log.AppLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

object WebmaskParser {

    private const val TAG = "WebmaskParser"

    /** webmask 文件头长度（"MASK"4 + version4 + reserved4 + segmentCount4 = 16B）。 */
    const val HEADER_SIZE = 16

    /** 段索引表每条目大小（timeMs 8 + byteOffset 8 = 16B）。 */
    const val META_ENTRY_SIZE = 16

    /**
     * 仅解析 webmask 文件头（前 16 字节），返回段总数。
     *
     * 返回值：
     *  - > 0：段总数（[parseSegmentMeta] 需用此数下载 N × 16 字节索引表）
     *  - <= 0：文件无效 / 损坏
     */
    fun parseSegmentCount(headerBytes: ByteArray): Int {
        if (headerBytes.size < HEADER_SIZE) return -1
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val tag = ByteArray(4)
        buf.get(tag)
        if (!tag.contentEquals("MASK".toByteArray())) {
            AppLog.e(TAG, "Invalid webmask header")
            return -1
        }
        buf.int  // version
        buf.int  // reserved
        val count = buf.int
        return if (count in 1..10_000) count else {
            AppLog.e(TAG, "Invalid segment count: $count")
            -1
        }
    }

    /**
     * 解析段索引表（segmentCount × 16 字节），构建延迟加载 [DmMaskData]。
     *
     * 索引表后续每段的字节数据由 [DmMaskRepository] 按需 Range 下载，
     * 填充到对应 [LazyMaskSegment.segData] 后再调用 [parseSegmentFrames]。
     */
    fun parseSegmentMeta(
        metaBytes: ByteArray,
        segmentCount: Int,
        fileTotalSize: Long,
        fps: Int,
    ): DmMaskData? {
        if (metaBytes.size < segmentCount * META_ENTRY_SIZE) {
            AppLog.e(TAG, "Meta bytes too small: ${metaBytes.size} need ${segmentCount * META_ENTRY_SIZE}")
            return null
        }
        val buf = ByteBuffer.wrap(metaBytes).order(ByteOrder.BIG_ENDIAN)
        val times = LongArray(segmentCount)
        val offsets = LongArray(segmentCount)
        for (i in 0 until segmentCount) {
            times[i] = buf.long
            offsets[i] = buf.long
        }
        val segments = (0 until segmentCount).map { i ->
            val byteEnd = if (i + 1 < segmentCount) offsets[i + 1] else fileTotalSize
            LazyMaskSegment(timeMs = times[i], byteOffset = offsets[i], byteEnd = byteEnd)
        }
        if (segments.isEmpty()) return null
        return DmMaskData(fps = fps, rawSegments = segments, totalFileSize = fileTotalSize)
    }

    /**
     * 兼容入口：从完整 webmask 文件字节构建（全量下载场景 / 测试）。
     *
     * 会把每段的字节切片预填到 [LazyMaskSegment.segData]，所以下次 [parseSegmentFrames] 直接可用。
     */
    fun parse(data: ByteArray, fps: Int = 0): DmMaskData? {
        val segCount = parseSegmentCount(data)
        if (segCount <= 0) return null
        val metaEnd = HEADER_SIZE + segCount * META_ENTRY_SIZE
        if (data.size < metaEnd) return null

        val metaBytes = data.copyOfRange(HEADER_SIZE, metaEnd)
        val dmData = parseSegmentMeta(metaBytes, segCount, data.size.toLong(), fps) ?: return null
        // 预填字节数据，让后续 parseSegmentFrames 不用再 Range 下载
        for (seg in dmData.rawSegments) {
            val s = seg.byteOffset.toInt()
            val e = seg.byteEnd.toInt().coerceAtMost(data.size)
            if (s in 0..<e) seg.segData = data.copyOfRange(s, e)
        }
        return dmData
    }

    fun parseSegmentFrames(segment: LazyMaskSegment, fps: Int, segDurationMs: Long = 0L): List<MaskFrame>? {
        val segBytes = segment.segData ?: return null
        if (segBytes.isEmpty()) return null

        val decompressed = try {
            GZIPInputStream(segBytes.inputStream()).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "GZIP decompress failed: ${e.message}")
            return null
        }

        val separator = "data:image/svg+xml;base64,".toByteArray()
        val parts = splitBy(decompressed, separator)
        if (parts.size <= 1) return null

        var emptyCount = 0
        val totalFrames = parts.size - 1
        val frames = mutableListOf<MaskFrame>()
        for (frameIdx in 1 until parts.size) {
            val localIdx = frameIdx - 1
            val ptsMs = if (totalFrames > 1 && segDurationMs > 0L) {
                segment.timeMs + (localIdx.toLong() * segDurationMs) / totalFrames
            } else if (fps > 0) {
                segment.timeMs + localIdx.toLong() * 1000L / fps
            } else {
                segment.timeMs
            }
            val b64Data = parts[frameIdx]
            val svgBytes = try {
                Base64.decode(b64Data, Base64.DEFAULT)
            } catch (e: Exception) {
                frames.add(MaskFrame(presentationTimeMs = ptsMs, paths = emptyList()))
                emptyCount++
                continue
            }
            val svgText = svgBytes.toString(Charsets.UTF_8)
            val parsed = parseSvgPaths(svgText)
            if (parsed.paths.isEmpty()) emptyCount++
            frames.add(
                MaskFrame(
                    presentationTimeMs = ptsMs,
                    paths = parsed.paths,
                    svgWidth = parsed.width,
                    svgHeight = parsed.height,
                )
            )
        }

        // 前向填充：空帧用前一个有 path 的帧替代，避免遮罩冻结。
        var lastFrame: MaskFrame? = null
        for (i in frames.indices) {
            if (frames[i].paths.isNotEmpty()) {
                lastFrame = frames[i]
            } else if (lastFrame != null) {
                // 保留当前帧的 PTS，只继承 paths/svgWidth/svgHeight
                frames[i] = MaskFrame(
                    presentationTimeMs = frames[i].presentationTimeMs,
                    paths = lastFrame.paths,
                    svgWidth = lastFrame.svgWidth,
                    svgHeight = lastFrame.svgHeight,
                )
                emptyCount--
            }
        }

        // 空帧是正常的（视频里某段时间没人物 → 源数据本来就空），不报警。
        return frames.takeIf { it.isNotEmpty() }
    }

    private fun splitBy(data: ByteArray, delimiter: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        while (i <= data.size - delimiter.size) {
            var match = true
            for (j in delimiter.indices) {
                if (data[i + j] != delimiter[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                result.add(data.copyOfRange(start, i))
                start = i + delimiter.size
                i = start
            } else {
                i++
            }
        }
        result.add(data.copyOfRange(start, data.size))
        return result
    }

    /**
     * 解析 SVG 文本，返回 path 列表与 SVG 标定尺寸。SVG 尺寸是渲染时缩放的关键——
     * 横屏视频常见 320×180、竖屏可能是 180×320，硬编码会导致严重错位。
     */
    private data class ParsedSvg(val paths: List<Path>, val width: Int, val height: Int)

    private fun parseSvgPaths(svgText: String): ParsedSvg {
        val viewWidth = extractFloat(svgText, """width="([\d.]+)px"""")
            ?: return ParsedSvg(emptyList(), 0, 0)
        val viewHeight = extractFloat(svgText, """height="([\d.]+)px"""")
            ?: return ParsedSvg(emptyList(), 0, 0)
        if (viewWidth <= 0f || viewHeight <= 0f) return ParsedSvg(emptyList(), 0, 0)

        val pathRegex = Regex("""<path\s+d="([^"]+)"""")
        val results = mutableListOf<Path>()

        for (match in pathRegex.findAll(svgText)) {
            val d = match.groupValues[1]
            val path = svgPathToAndroidPath(d, viewWidth, viewHeight)
            if (path != null) {
                results.add(path)
            }
        }
        return ParsedSvg(results, viewWidth.toInt(), viewHeight.toInt())
    }

    private fun svgPathToAndroidPath(d: String, viewWidth: Float, viewHeight: Float): Path? {
        try {
            // webmask 协议的 path 是「整画面减人物」的带洞填充，B 站编码端用 SVG fill-rule="evenodd"
            // 表达"外圈减内圈"——外轮廓 + 人物轮廓内圈相消才能让人物区域成为"洞"。
            // Android Path 默认 WINDING（非零环绕规则）会把内圈也算成 fill 内部，clipPath 时
            // 人物区域反而被算作"允许绘制"，弹幕从人物身上漏出来——必须显式改成 EVEN_ODD。
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            val tokens = tokenizeSvgPath(d.trim())
            var i = 0
            var currentCommand = 'M'
            var currentX = 0f
            var currentY = 0f
            var lastCubicCtrlX: Float? = null
            var lastCubicCtrlY: Float? = null
            var lastQuadCtrlX: Float? = null
            var lastQuadCtrlY: Float? = null

            fun resetSmoothControls() {
                lastCubicCtrlX = null
                lastCubicCtrlY = null
                lastQuadCtrlX = null
                lastQuadCtrlY = null
            }

            fun absoluteX(value: String): Float = value.toFloat() * 0.1f
            fun absoluteY(value: String): Float = viewHeight - value.toFloat() * 0.1f
            fun relativeX(value: String): Float = value.toFloat() * 0.1f
            fun relativeY(value: String): Float = value.toFloat() * -0.1f

            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token.length == 1 && token[0] in "MLmlCcSsQqTtAaHhVv" -> {
                        currentCommand = token[0]
                        i++
                        continue
                    }
                    token == "z" || token == "Z" -> {
                        path.close()
                        resetSmoothControls()
                        i++
                        continue
                    }
                    token[0].isDigit() || token[0] == '-' || token[0] == '.' -> { /* keep currentCommand */ }
                    else -> { i++; continue }
                }

                when (currentCommand) {
                    'M' -> {
                        if (i + 1 >= tokens.size) break
                        currentX = absoluteX(tokens[i])
                        currentY = absoluteY(tokens[i + 1])
                        path.moveTo(currentX, currentY)
                        resetSmoothControls()
                        i += 2
                        currentCommand = 'L'
                    }
                    'L' -> {
                        if (i + 1 >= tokens.size) break
                        currentX = absoluteX(tokens[i])
                        currentY = absoluteY(tokens[i + 1])
                        path.lineTo(currentX, currentY)
                        resetSmoothControls()
                        i += 2
                    }
                    'H' -> {
                        currentX = absoluteX(tokens[i])
                        path.lineTo(currentX, currentY)
                        resetSmoothControls()
                        i += 1
                    }
                    'V' -> {
                        currentY = absoluteY(tokens[i])
                        path.lineTo(currentX, currentY)
                        resetSmoothControls()
                        i += 1
                    }
                    'm' -> {
                        if (i + 1 >= tokens.size) break
                        val dx = relativeX(tokens[i])
                        val dy = relativeY(tokens[i + 1])
                        path.rMoveTo(dx, dy)
                        currentX += dx
                        currentY += dy
                        resetSmoothControls()
                        i += 2
                        currentCommand = 'l'
                    }
                    'l' -> {
                        if (i + 1 >= tokens.size) break
                        val dx = relativeX(tokens[i])
                        val dy = relativeY(tokens[i + 1])
                        path.rLineTo(dx, dy)
                        currentX += dx
                        currentY += dy
                        resetSmoothControls()
                        i += 2
                    }
                    'h' -> {
                        val dx = relativeX(tokens[i])
                        path.rLineTo(dx, 0f)
                        currentX += dx
                        resetSmoothControls()
                        i += 1
                    }
                    'v' -> {
                        val dy = relativeY(tokens[i])
                        path.rLineTo(0f, dy)
                        currentY += dy
                        resetSmoothControls()
                        i += 1
                    }
                    'C' -> {
                        if (i + 5 >= tokens.size) break
                        val x1 = absoluteX(tokens[i])
                        val y1 = absoluteY(tokens[i + 1])
                        val x2 = absoluteX(tokens[i + 2])
                        val y2 = absoluteY(tokens[i + 3])
                        val x = absoluteX(tokens[i + 4])
                        val y = absoluteY(tokens[i + 5])
                        path.cubicTo(
                            x1, y1,
                            x2, y2,
                            x, y
                        )
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        currentX = x
                        currentY = y
                        i += 6
                    }
                    'S' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentX - lastCubicCtrlX!!
                        } else {
                            currentX
                        }
                        val y1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentY - lastCubicCtrlY!!
                        } else {
                            currentY
                        }
                        val x2 = absoluteX(tokens[i])
                        val y2 = absoluteY(tokens[i + 1])
                        val x = absoluteX(tokens[i + 2])
                        val y = absoluteY(tokens[i + 3])
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        currentX = x
                        currentY = y
                        i += 4
                    }
                    'c' -> {
                        if (i + 5 >= tokens.size) break
                        val x1 = currentX + relativeX(tokens[i])
                        val y1 = currentY + relativeY(tokens[i + 1])
                        val x2 = currentX + relativeX(tokens[i + 2])
                        val y2 = currentY + relativeY(tokens[i + 3])
                        val dx = relativeX(tokens[i + 4])
                        val dy = relativeY(tokens[i + 5])
                        path.rCubicTo(
                            relativeX(tokens[i]), relativeY(tokens[i + 1]),
                            relativeX(tokens[i + 2]), relativeY(tokens[i + 3]),
                            dx, dy
                        )
                        currentX += dx
                        currentY += dy
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        i += 6
                    }
                    's' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentX - lastCubicCtrlX!!
                        } else {
                            currentX
                        }
                        val y1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentY - lastCubicCtrlY!!
                        } else {
                            currentY
                        }
                        val x2 = currentX + relativeX(tokens[i])
                        val y2 = currentY + relativeY(tokens[i + 1])
                        val dx = relativeX(tokens[i + 2])
                        val dy = relativeY(tokens[i + 3])
                        val x = currentX + dx
                        val y = currentY + dy
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        currentX = x
                        currentY = y
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        i += 4
                    }
                    'Q' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = absoluteX(tokens[i])
                        val y1 = absoluteY(tokens[i + 1])
                        val x = absoluteX(tokens[i + 2])
                        val y = absoluteY(tokens[i + 3])
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 4
                    }
                    'q' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = currentX + relativeX(tokens[i])
                        val y1 = currentY + relativeY(tokens[i + 1])
                        val dx = relativeX(tokens[i + 2])
                        val dy = relativeY(tokens[i + 3])
                        val x = currentX + dx
                        val y = currentY + dy
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 4
                    }
                    'T' -> {
                        if (i + 1 >= tokens.size) break
                        val x1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentX - lastQuadCtrlX!!
                        } else {
                            currentX
                        }
                        val y1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentY - lastQuadCtrlY!!
                        } else {
                            currentY
                        }
                        val x = absoluteX(tokens[i])
                        val y = absoluteY(tokens[i + 1])
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 2
                    }
                    't' -> {
                        if (i + 1 >= tokens.size) break
                        val x1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentX - lastQuadCtrlX!!
                        } else {
                            currentX
                        }
                        val y1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentY - lastQuadCtrlY!!
                        } else {
                            currentY
                        }
                        val dx = relativeX(tokens[i])
                        val dy = relativeY(tokens[i + 1])
                        val x = currentX + dx
                        val y = currentY + dy
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 2
                    }
                    'A' -> {
                        if (i + 6 >= tokens.size) break
                        currentX = absoluteX(tokens[i + 5])
                        currentY = absoluteY(tokens[i + 6])
                        path.lineTo(currentX, currentY)
                        resetSmoothControls()
                        i += 7
                    }
                    'a' -> {
                        if (i + 6 >= tokens.size) break
                        val dx = relativeX(tokens[i + 5])
                        val dy = relativeY(tokens[i + 6])
                        path.rLineTo(dx, dy)
                        currentX += dx
                        currentY += dy
                        resetSmoothControls()
                        i += 7
                    }
                    else -> i++
                }
            }
            return path
        } catch (e: Exception) {
            AppLog.e(TAG, "SVG path parse error: ${e.message}")
            return null
        }
    }

    private fun tokenizeSvgPath(d: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        val commands = "MmLlCcSsQqTtAaHhVvZz"

        for (ch in d) {
            if (ch in commands) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch == ',') {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }

    private fun extractFloat(text: String, pattern: String): Float? {
        return Regex(pattern).find(text)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
