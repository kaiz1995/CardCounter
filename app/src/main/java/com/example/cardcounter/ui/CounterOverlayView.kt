package com.example.cardcounter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.example.cardcounter.capture.OverlayProbeSnapshot
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase

/**
 * 全屏只读显示层：
 * 1) 五个对手累计出牌框；
 * 2) 底部外面剩余横条（手牌未锁定时不显示数字，只标“手牌识别中”）；
 * 3) 顶部手牌点数条（只在识别中显示，锁定后显示 2 秒“手牌已确认（共 N 张）”再收起）；
 * 4) 停记时在 stopBlock 画一块提示，不再给 6 个框套红边。
 */
class CounterOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var state: GameState? = null
    private var probe: OverlayProbeSnapshot = OverlayProbeSnapshot()
    private var showConfirmedBanner = false

    fun render(
        state: GameState,
        probe: OverlayProbeSnapshot = OverlayProbeSnapshot(),
        showConfirmedBanner: Boolean = false
    ) {
        this.state = state
        this.probe = probe
        this.showConfirmedBanner = showConfirmedBanner
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
                current?.playedCountBy(seat) ?: 0
            )
        }
        drawOutsideRemaining(canvas, current)
        drawHandBar(canvas, current)
        drawStopBlock(canvas, current)
        canvas.restore()
    }

    private fun drawPanel(
        canvas: Canvas,
        rect: OverlayLayout.Rect,
        counts: RankCounts,
        playedCount: Int
    ) {
        drawFrame(canvas, rect) {
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
        val handConfirmed = current?.handConfirmed == true
        val recognizing = !handConfirmed &&
            !probe.handLocked &&
            !probe.gameSkipped &&
            current?.phase == SessionPhase.ACTIVE
        drawFrame(canvas, rect) {
            val cellWidth = rect.width.toFloat() / Rank.entries.size
            val headerHeight = 36f
            val remaining = if (handConfirmed) current?.outsideRemaining else null
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
                    remaining?.get(rank)?.toString().orEmpty(),
                    23f,
                    COLOR_RED,
                    COLOR_VALUE
                )
            }
        }
        if (recognizing) drawRecognizingTag(canvas)
    }

    /** 顶部手牌点数条：只在识别中显示 15 个点数；锁定后显示 2 秒确认提示再收起。 */
    private fun drawHandBar(canvas: Canvas, current: GameState?) {
        if (current?.phase != SessionPhase.ACTIVE || probe.gameSkipped) return
        val rect = OverlayLayout.handBar
        if (!current.handConfirmed && !probe.handLocked) {
            drawFrame(canvas, rect, cornerRadius = 6f) {
                val cellWidth = rect.width.toFloat() / Rank.entries.size
                val headerHeight = 25f
                val latest = probe.latestHandCards
                Rank.entries.forEachIndexed { index, rank ->
                    val left = rect.x + index * cellWidth
                    drawCell(
                        canvas, left, rect.y.toFloat(), cellWidth, headerHeight,
                        rank.label, 15f, COLOR_TEXT, COLOR_HEADER
                    )
                    val count = latest?.get(rank)
                    drawCell(
                        canvas,
                        left,
                        rect.y + headerHeight,
                        cellWidth,
                        rect.height - headerHeight,
                        count?.toString() ?: "—",
                        16f,
                        if (count == null || count == 0) COLOR_ZERO else COLOR_SELF_COUNT,
                        COLOR_VALUE
                    )
                }
            }
            return
        }
        if (showConfirmedBanner) {
            val count = current.selfHand?.total() ?: probe.handConfirmedCount ?: 0
            val bounds = rect.asRectF()
            paint.style = Paint.Style.FILL
            paint.color = COLOR_CONFIRMED_BG
            canvas.drawRoundRect(bounds, 10f, 10f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = COLOR_CONFIRMED_BORDER
            canvas.drawRoundRect(bounds, 10f, 10f, paint)
            paint.style = Paint.Style.FILL
            paint.color = COLOR_TEXT
            paint.textSize = 20f
            val metrics = paint.fontMetrics
            val baseline = bounds.centerY() - (metrics.bottom + metrics.top) / 2f
            canvas.drawText("手牌已确认（共 ${count} 张）", bounds.centerX(), baseline, paint)
        }
    }

    /** 停记时只在 stopBlock 画一块：标题、一行 PauseReason 文案、一行“长按「开新局」重新开始”。 */
    private fun drawStopBlock(canvas: Canvas, current: GameState?) {
        val reason = current?.pauseReason
        val skipped = probe.gameSkipped && reason == null
        if (reason == null && !skipped) return
        val rect = OverlayLayout.stopBlock
        val bounds = rect.asRectF()
        paint.style = Paint.Style.FILL
        paint.color = COLOR_STOP_BG
        canvas.drawRoundRect(bounds, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = COLOR_RED
        canvas.drawRoundRect(bounds, 10f, 10f, paint)

        val title = if (reason != null) "记牌已停" else "本局未记"
        val detail = reason?.displayName ?: "中途打开，等下一局"
        val hint = "长按「开新局」重新开始"
        val maxTextWidth = rect.width - 10f

        drawFittedLine(canvas, title, bounds.centerX(), rect.y + 15f, maxTextWidth, 13f, COLOR_RED)
        drawFittedLine(canvas, detail, bounds.centerX(), rect.y + 35f, maxTextWidth, 10.5f, COLOR_TEXT)
        drawFittedLine(canvas, hint, bounds.centerX(), rect.y + 54f, maxTextWidth, 10f, COLOR_HINT_TEXT)
    }

    private fun drawFittedLine(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        preferredSize: Float,
        color: Int
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = preferredSize
        val measured = paint.measureText(text)
        if (measured > maxWidth && maxWidth > 0f) {
            paint.textSize = (preferredSize * maxWidth / measured).coerceAtLeast(7.5f)
        }
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.bottom + metrics.top) / 2f
        canvas.drawText(text, centerX, baseline, paint)
    }

    /** 横条左上方的提示标签：手牌还没锁定时标出“手牌识别中”。 */
    private fun drawRecognizingTag(canvas: Canvas) {
        val tag = OverlayLayout.handRecognizingTag.asRectF()
        paint.style = Paint.Style.FILL
        paint.color = COLOR_RECOGNIZING_BG
        canvas.drawRoundRect(tag, 8f, 8f, paint)
        paint.style = Paint.Style.FILL
        paint.color = COLOR_TEXT
        paint.textSize = 15f
        val metrics = paint.fontMetrics
        val baseline = tag.centerY() - (metrics.bottom + metrics.top) / 2f
        canvas.drawText("手牌识别中", tag.centerX(), baseline, paint)
    }

    private inline fun drawFrame(
        canvas: Canvas,
        rect: OverlayLayout.Rect,
        cornerRadius: Float = 12f,
        content: () -> Unit
    ) {
        val bounds = rect.asRectF()
        val clip = Path().apply { addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = COLOR_FRAME
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
        content()
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.BLACK
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
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
        if (text.isEmpty()) return
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
        val COLOR_HINT_TEXT = Color.rgb(212, 214, 218)
        val COLOR_ZERO = Color.rgb(111, 114, 117)
        val COLOR_RED = Color.rgb(255, 51, 74)
        val COLOR_SELF_COUNT = Color.rgb(255, 224, 130)
        val COLOR_RECOGNIZING_BG = Color.rgb(44, 92, 148)
        val COLOR_CONFIRMED_BG = Color.rgb(26, 108, 62)
        val COLOR_CONFIRMED_BORDER = Color.rgb(88, 206, 136)
        val COLOR_STOP_BG = Color.argb(235, 36, 14, 18)
    }
}
