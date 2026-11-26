package com.example.cardcounter.vision

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 送进模型的画面缩放，和训练/评测端（tools/training/common.py、make_golden.py）同一套算法，确定性、纯 Kotlin：
 * 1. 整屏用区域平均（cv2.INTER_AREA）缩到 1280×578 画布；屏幕比画布小时和 cv2 一样退回双线性。
 * 2. 画布上的取图区再用双线性（cv2.INTER_LINEAR，半像素对齐）缩到模型要的大小。
 * 3. 出牌区等比缩放后居中贴到 320×320，偏移取整数像素，四周填 [LETTERBOX_PAD]。
 * 和 cv2 逐像素比较，最大差 ≤ 1（见 ScreenScaleTest）。
 *
 * 不再用整数跳点取最近邻：手机上最近邻会让细线条的牌角时有时无，同一张牌的把握度比电脑端低一截。
 */

/** 能按坐标取 RGB 的图；返回 0xRRGGBB。 */
abstract class Raster(val width: Int, val height: Int) {
    abstract fun rgb(x: Int, y: Int): Int
}

/** Android ImageReader 的 RGBA_8888 平面（字节顺序 R、G、B、A）。 */
class RgbaRaster(
    private val buffer: ByteBuffer,
    width: Int,
    height: Int,
    private val rowStride: Int,
    private val pixelStride: Int
) : Raster(width, height) {
    override fun rgb(x: Int, y: Int): Int {
        val offset = y * rowStride + x * pixelStride
        return ((buffer.get(offset).toInt() and 0xff) shl 16) or
            ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
            (buffer.get(offset + 2).toInt() and 0xff)
    }
}

/** 已经在内存里的 ARGB 像素（行优先）。 */
class ArgbRaster(private val pixels: IntArray, width: Int, height: Int) : Raster(width, height) {
    init {
        require(pixels.size == width * height) { "像素数 ${pixels.size} 和尺寸 ${width}x$height 对不上" }
    }

    override fun rgb(x: Int, y: Int): Int = pixels[y * width + x] and 0xffffff
}

/** 一个方向上，每个目标像素由哪几个源像素按什么权重加权得到。 */
internal class AxisWeights(val start: IntArray, val weights: Array<FloatArray>) {
    companion object {
        /** 区域平均：目标像素覆盖的源区间内，各源像素按重叠长度加权（等价 cv2.INTER_AREA 缩小）。 */
        fun area(src: Int, dst: Int): AxisWeights {
            val scale = src.toDouble() / dst
            val start = IntArray(dst)
            val weights = Array(dst) { FloatArray(0) }
            for (i in 0 until dst) {
                val lo = i * scale
                val hi = (i + 1) * scale
                val first = floor(lo).toInt().coerceIn(0, src - 1)
                val last = min(ceil(hi).toInt(), src)
                val raw = DoubleArray(max(1, last - first))
                for (j in first until last) raw[j - first] = min(hi, j + 1.0) - max(lo, j.toDouble())
                val total = raw.sum()
                start[i] = first
                weights[i] = FloatArray(raw.size) { (raw[it] / total).toFloat() }
            }
            return AxisWeights(start, weights)
        }

        /** 双线性：源坐标 = (i + 0.5) * src / dst - 0.5，两端夹取（等价 cv2.INTER_LINEAR）。 */
        fun linear(src: Int, dst: Int): AxisWeights {
            val scale = src.toDouble() / dst
            val start = IntArray(dst)
            val weights = Array(dst) { FloatArray(0) }
            for (i in 0 until dst) {
                val position = (i + 0.5) * scale - 0.5
                val base = floor(position).toInt()
                val fraction = (position - base).toFloat()
                when {
                    base < 0 -> { start[i] = 0; weights[i] = floatArrayOf(1f) }
                    base >= src - 1 -> { start[i] = src - 1; weights[i] = floatArrayOf(1f) }
                    else -> { start[i] = base; weights[i] = floatArrayOf(1f - fraction, fraction) }
                }
            }
            return AxisWeights(start, weights)
        }
    }
}

object ScreenScale {
    const val CANVAS_WIDTH = 1_280
    const val CANVAS_HEIGHT = 578
    const val MODEL_SIZE = 320

    /** letterbox 的填充灰度，和训练端 LETTERBOX_PAD 一致。 */
    const val LETTERBOX_PAD = 114

    /** 手牌条带在 870 宽方形画布里的起始行，和训练端 HAND_PAD_TOP 一致。 */
    const val HAND_PAD_TOP = 540

    private val weightCache = HashMap<Triple<Int, Int, Boolean>, AxisWeights>()

    @Synchronized
    private fun weights(src: Int, dst: Int, area: Boolean): AxisWeights =
        weightCache.getOrPut(Triple(src, dst, area)) {
            if (area) AxisWeights.area(src, dst) else AxisWeights.linear(src, dst)
        }

    /**
     * 整屏缩到 1280×578 画布后，取画布上 [left, left+width) × [top, top+height) 这一块，返回不透明 ARGB 像素。
     * 只算这一块用到的源行，不整屏缩。
     */
    fun canvasRegion(frame: Raster, left: Int, top: Int, width: Int, height: Int): IntArray {
        require(left >= 0 && top >= 0 && width > 0 && height > 0 &&
            left + width <= CANVAS_WIDTH && top + height <= CANVAS_HEIGHT) {
            "取图区 $left,$top ${width}x$height 超出 ${CANVAS_WIDTH}x$CANVAS_HEIGHT 画布"
        }
        // cv2.INTER_AREA 只在两个方向都是缩小时按面积平均，否则按双线性。
        val area = frame.width >= CANVAS_WIDTH && frame.height >= CANVAS_HEIGHT
        return resample(
            frame,
            weights(frame.width, CANVAS_WIDTH, area),
            weights(frame.height, CANVAS_HEIGHT, area),
            left, top, width, height
        )
    }

    /** 双线性缩放一整张 ARGB 图。 */
    fun resizeLinear(pixels: IntArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): IntArray =
        resample(
            ArgbRaster(pixels, srcWidth, srcHeight),
            weights(srcWidth, dstWidth, false),
            weights(srcHeight, dstHeight, false),
            0, 0, dstWidth, dstHeight
        )

    /**
     * 等比缩放居中贴到 [size]×[size]：缩放比取 min(size/宽, size/高)，边长四舍五入取偶（和 Python round 一致），
     * 偏移 (size - 边长) / 2 取整数像素，四周填 [pad]。
     */
    fun letterbox(region: IntArray, width: Int, height: Int, size: Int = MODEL_SIZE, pad: Int = LETTERBOX_PAD): IntArray {
        val scale = min(size.toDouble() / width, size.toDouble() / height)
        val newWidth = max(1, Math.rint(width * scale).toInt())
        val newHeight = max(1, Math.rint(height * scale).toInt())
        val resized = resizeLinear(region, width, height, newWidth, newHeight)
        val gray = 0xff000000.toInt() or (pad shl 16) or (pad shl 8) or pad
        val output = IntArray(size * size) { gray }
        val dx = (size - newWidth) / 2
        val dy = (size - newHeight) / 2
        for (y in 0 until newHeight) {
            System.arraycopy(resized, y * newWidth, output, (dy + y) * size + dx, newWidth)
        }
        return output
    }

    /** 出牌区：从画布上取 [left, top, width, height] 这一块，等比缩放居中，得到 320×320 模型输入。 */
    fun playZoneInput(frame: Raster, left: Int, top: Int, width: Int, height: Int): IntArray =
        letterbox(canvasRegion(frame, left, top, width, height), width, height)

    /**
     * 手牌画布：1280×578 画布上 (left, top, right, bottom) 的条带放到 (right-left) 见方的黑底第 [HAND_PAD_TOP] 行起，
     * 和训练端 hand_canvas 同形（870×870）。证据图存的就是它，模型输入由 [handInput] 缩出来，事后能原样复跑。
     */
    fun handCanvas(frame: Raster, left: Int, top: Int, right: Int, bottom: Int): IntArray =
        handCanvasFromRegion(canvasRegion(frame, left, top, right - left, bottom - top), left, top, right - left, left, top, right, bottom)

    /**
     * 同 [handCanvas]，但条带从已经缩好的画布区域 [region]（左上角在画布 ([regionLeft], [regionTop])、宽 [regionWidth]）里切，
     * 不再重新缩一遍：证据截图和模型输入共用同一次缩放，像素一致。
     */
    fun handCanvasFromRegion(
        region: IntArray,
        regionLeft: Int,
        regionTop: Int,
        regionWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntArray {
        val width = right - left
        val height = bottom - top
        require(width > 0 && height > 0 && HAND_PAD_TOP + height <= width) {
            "手牌条带 ${width}x$height 放不进 ${width}x$width 画布的第 $HAND_PAD_TOP 行起"
        }
        require(left >= regionLeft && top >= regionTop && right - regionLeft <= regionWidth &&
            (bottom - regionTop) * regionWidth <= region.size) {
            "手牌条带 $left,$top,$right,$bottom 不在取到的区域内"
        }
        val canvas = IntArray(width * width) { 0xff000000.toInt() }
        for (y in 0 until height) {
            System.arraycopy(region, (top - regionTop + y) * regionWidth + (left - regionLeft), canvas, (HAND_PAD_TOP + y) * width, width)
        }
        return canvas
    }

    /** 手牌画布（[width] 见方）→ 320×320 模型输入：双线性，和训练端 cv2.INTER_LINEAR 一致。 */
    fun handInput(canvas: IntArray, width: Int): IntArray = resizeLinear(canvas, width, width, MODEL_SIZE, MODEL_SIZE)

    private fun resample(
        src: Raster,
        wx: AxisWeights,
        wy: AxisWeights,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ): IntArray {
        var rowMin = Int.MAX_VALUE
        var rowMax = -1
        for (y in top until top + height) {
            rowMin = min(rowMin, wy.start[y])
            rowMax = max(rowMax, wy.start[y] + wy.weights[y].size - 1)
        }
        val rows = rowMax - rowMin + 1
        // 先横向：需要的每个源行，横向缩成 width 个像素（红、绿、蓝各一个浮点）。
        val middle = FloatArray(rows * width * 3)
        for (row in 0 until rows) {
            val sourceY = rowMin + row
            for (x in 0 until width) {
                val tx = left + x
                val first = wx.start[tx]
                val weights = wx.weights[tx]
                var red = 0f
                var green = 0f
                var blue = 0f
                for (k in weights.indices) {
                    val color = src.rgb(first + k, sourceY)
                    val weight = weights[k]
                    red += ((color shr 16) and 0xff) * weight
                    green += ((color shr 8) and 0xff) * weight
                    blue += (color and 0xff) * weight
                }
                val offset = (row * width + x) * 3
                middle[offset] = red
                middle[offset + 1] = green
                middle[offset + 2] = blue
            }
        }
        // 再纵向。
        val output = IntArray(width * height)
        for (y in 0 until height) {
            val ty = top + y
            val first = wy.start[ty] - rowMin
            val weights = wy.weights[ty]
            for (x in 0 until width) {
                var red = 0f
                var green = 0f
                var blue = 0f
                for (k in weights.indices) {
                    val offset = ((first + k) * width + x) * 3
                    val weight = weights[k]
                    red += middle[offset] * weight
                    green += middle[offset + 1] * weight
                    blue += middle[offset + 2] * weight
                }
                output[y * width + x] = 0xff000000.toInt() or (toByte(red) shl 16) or (toByte(green) shl 8) or toByte(blue)
            }
        }
        return output
    }

    private fun toByte(value: Float): Int = floor(value + 0.5f).toInt().coerceIn(0, 255)
}
