package com.example.cardcounter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts

/** 参考原版布局：五个对手累计出牌框 + 底部外面剩余框。 */
class CounterOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var state: GameState? = null

    fun render(state: GameState) {
        this.state = state
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(
            width.toFloat() / OverlayLayout.DESIGN_WIDTH,
            height.toFloat() / OverlayLayout.DESIGN_HEIGHT
        )
        val current = state
        OverlayLayout.seatPanels.forEach { (seat, rect) ->
            drawPanel(
                canvas,
                rect,
                current?.playedBy(seat) ?: RankCounts.zero(),
                current?.playedCountBy(seat) ?: 0,
                current?.pauseReason != null
            )
        }
        drawOutsideRemaining(canvas, current)
        canvas.restore()
    }

    private fun drawPanel(
        canvas: Canvas,
        rect: OverlayLayout.Rect,
        counts: RankCounts,
        playedCount: Int,
        paused: Boolean
    ) {
        drawFrame(canvas, rect, paused) {
            val lastWidth = 27f
            val cellWidth = (rect.width - lastWidth) / Rank.entries.size
            val headerHeight = 25f
            Rank.entries.forEachIndexed { index, rank ->
                val left = rect.x + index * cellWidth
                drawCell(
                    canvas, left, rect.y.toFloat(), cellWidth, headerHeight,
                    rank.label, 15f, COLOR_TEXT, COLOR_HEADER
                )
                val value = counts[rank]
                drawCell(
                    canvas,
                    left,
                    rect.y + headerHeight,
                    cellWidth,
                    rect.height - headerHeight,
                    value.toString(),
                    16f,
                    if (value == 0) COLOR_ZERO else COLOR_RED,
                    COLOR_VALUE
                )
            }
            val lastLeft = rect.x + rect.width - lastWidth
            drawCell(
                canvas, lastLeft, rect.y.toFloat(), lastWidth, headerHeight,
                "出", 15f, COLOR_TEXT, COLOR_HEADER
            )
            drawCell(
                canvas,
                lastLeft,
                rect.y + headerHeight,
                lastWidth,
                rect.height - headerHeight,
                playedCount.toString(),
                16f,
                COLOR_RED,
                COLOR_VALUE
            )
        }
    }

    private fun drawOutsideRemaining(canvas: Canvas, current: GameState?) {
        val rect = OverlayLayout.outsideRemaining
        drawFrame(canvas, rect, current?.pauseReason != null) {
            val cellWidth = rect.width.toFloat() / Rank.entries.size
            val headerHeight = 36f
            val remaining = current?.outsideRemaining
            Rank.entries.forEachIndexed { index, rank ->
                val left = rect.x + index * cellWidth
                drawCell(
                    canvas, left, rect.y.toFloat(), cellWidth, headerHeight,
                    rank.label, 22f, COLOR_TEXT, COLOR_HEADER
                )
                drawCell(
                    canvas,
                    left,
                    rect.y + headerHeight,
                    cellWidth,
                    rect.height - headerHeight,
                    remaining?.get(rank)?.toString() ?: "—",
                    23f,
                    COLOR_RED,
                    COLOR_VALUE
                )
            }
        }
    }

    private inline fun drawFrame(
        canvas: Canvas,
        rect: OverlayLayout.Rect,
        paused: Boolean,
        content: () -> Unit
    ) {
        val bounds = rect.asRectF()
        val clip = Path().apply { addRoundRect(bounds, 12f, 12f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = COLOR_FRAME
        canvas.drawRoundRect(bounds, 12f, 12f, paint)
        content()
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (paused) COLOR_RED else Color.BLACK
        canvas.drawRoundRect(bounds, 12f, 12f, paint)
    }

    private fun drawCell(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        text: String,
        textSize: Float,
        textColor: Int,
        backgroundColor: Int
    ) {
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor
        canvas.drawRect(left, top, left + width, top + height, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = COLOR_GRID
        canvas.drawRect(left, top, left + width, top + height, paint)
        paint.style = Paint.Style.FILL
        paint.color = textColor
        paint.textSize = textSize
        val metrics = paint.fontMetrics
        val baseline = top + (height - metrics.bottom - metrics.top) / 2f
        canvas.drawText(text, left + width / 2f, baseline, paint)
    }

    private fun OverlayLayout.Rect.asRectF(): RectF =
        RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())

    private companion object {
        val COLOR_FRAME = Color.rgb(9, 10, 11)
        val COLOR_HEADER = Color.rgb(59, 62, 64)
        val COLOR_VALUE = Color.rgb(53, 56, 58)
        val COLOR_GRID = Color.rgb(24, 26, 28)
        val COLOR_TEXT = Color.rgb(245, 245, 245)
        val COLOR_ZERO = Color.rgb(111, 114, 117)
        val COLOR_RED = Color.rgb(255, 51, 74)
    }
}
