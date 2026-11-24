package com.example.cardcounter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.example.cardcounter.domain.HandPanelMode
import com.example.cardcounter.domain.HandPanelStatus
import com.example.cardcounter.domain.Rank

/**
 * 手牌面板的一块可触摸小窗：点数条（左/右）、合计与按钮、状态提示。
 * 每块都按自己的设计坐标矩形（[OverlayLayout] 里的 hand* 区域）画，由悬浮窗服务按同样坐标摆放。
 */
class HandPanelView(
    context: Context,
    val kind: Kind,
    initialRect: OverlayLayout.Rect,
    private val actions: Actions
) : View(context) {
    enum class Kind { RANKS_LEFT, RANKS_RIGHT, CONTROL, STATUS }

    /** 这块小窗当前占的设计坐标矩形；状态窗确认后缩成小条时由悬浮窗服务改。只在主线程读写。 */
    var rect: OverlayLayout.Rect = initialRect

    interface Actions {
        fun adjust(rank: Rank, delta: Int)
        fun toggleAddMode()
        fun confirm()
        fun toggleExpanded()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var status: HandPanelStatus? = null
    private var edited: Set<Rank> = emptySet()
    private var addMode = true
    private var expanded = false

    fun render(status: HandPanelStatus, edited: Set<Rank>, addMode: Boolean, expanded: Boolean) {
        this.status = status
        this.edited = edited
        this.addMode = addMode
        this.expanded = expanded
        invalidate()
    }

    private fun ranks(): List<Rank> = when (kind) {
        Kind.RANKS_LEFT -> OverlayLayout.handRanksLeftRanks
        Kind.RANKS_RIGHT -> OverlayLayout.handRanksRightRanks
        else -> emptyList()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (width == 0 || height == 0) return true
                val x = event.x * rect.width / width
                val y = event.y * rect.height / height
                onTap(x, y)
                return true
            }
        }
        return true
    }

    private fun onTap(x: Float, y: Float) {
        val current = status ?: return
        when (kind) {
            Kind.RANKS_LEFT, Kind.RANKS_RIGHT -> {
                if (!current.canEdit) return
                val list = ranks()
                val index = (x / (rect.width.toFloat() / list.size)).toInt().coerceIn(0, list.size - 1)
                actions.adjust(list[index], if (addMode) 1 else -1)
            }
            Kind.CONTROL -> when ((y / ROW_HEIGHT).toInt()) {
                1 -> if (current.canEdit) actions.toggleAddMode()
                2 -> if (current.canConfirm) actions.confirm()
            }
            Kind.STATUS -> if (current.mode == HandPanelMode.CONFIRMED) actions.toggleExpanded()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = status ?: return
        if (width == 0 || height == 0) return
        canvas.save()
        canvas.scale(width.toFloat() / rect.width, height.toFloat() / rect.height)
        when (kind) {
            Kind.RANKS_LEFT, Kind.RANKS_RIGHT -> drawRanks(canvas, current)
            Kind.CONTROL -> drawControl(canvas, current)
            Kind.STATUS -> drawStatus(canvas, current)
        }
        canvas.restore()
    }

    private fun drawRanks(canvas: Canvas, s: HandPanelStatus) {
        val list = ranks()
        val cellWidth = rect.width.toFloat() / list.size
        list.forEachIndexed { index, rank ->
            val left = index * cellWidth
            val userEdited = s.canEdit && rank in edited
            val value = s.shownCounts[rank]
            val headerBg = if (userEdited) COLOR_EDITED else COLOR_HEADER
            val valueBg = if (userEdited) COLOR_EDITED else COLOR_VALUE
            val headerText = if (userEdited) COLOR_DARK else COLOR_TEXT
            val valueText = when {
                userEdited -> COLOR_DARK
                value == 0 -> COLOR_ZERO
                else -> COLOR_RED
            }
            drawCell(canvas, left, 0f, cellWidth, HEADER_HEIGHT, rank.label, 20f, headerText, headerBg)
            drawCell(
                canvas, left, HEADER_HEIGHT, cellWidth, rect.height - HEADER_HEIGHT,
                value.toString(), 28f, valueText, valueBg
            )
        }
    }

    private fun drawControl(canvas: Canvas, s: HandPanelStatus) {
        val w = rect.width.toFloat()
        val totalColor = if (s.mismatch) COLOR_RED else COLOR_TEXT
        fillBox(canvas, 0f, 0f, w, ROW_HEIGHT, COLOR_VALUE)
        drawLabel(canvas, "点数合计 ${s.handTotal}", w / 2f, 17f, 20f, totalColor)
        drawLabel(canvas, "数出来 ${s.positionCount ?: "—"}", w / 2f, 43f, 20f, totalColor)

        fillBox(canvas, 0f, ROW_HEIGHT, w, ROW_HEIGHT, COLOR_EDITED)
        drawLabel(
            canvas, if (addMode) "点点数：加一张" else "点点数：减一张",
            w / 2f, ROW_HEIGHT + ROW_HEIGHT / 2f, 19f, COLOR_DARK
        )

        val enabled = s.canConfirm
        fillBox(canvas, 0f, ROW_HEIGHT * 2, w, ROW_HEIGHT, if (enabled) COLOR_OK else COLOR_DISABLED)
        drawLabel(
            canvas, "确认手牌", w / 2f, ROW_HEIGHT * 2 + ROW_HEIGHT / 2f, 24f,
            if (enabled) COLOR_TEXT else COLOR_ZERO
        )
    }

    private fun drawStatus(canvas: Canvas, s: HandPanelStatus) {
        val w = rect.width.toFloat()
        val h = rect.height.toFloat()
        if (s.mode == HandPanelMode.CONFIRMED) {
            fillBox(canvas, 0f, 0f, w, h, COLOR_OK)
            drawLabel(canvas, "手牌已确认", w / 2f, h * 0.33f, 21f, COLOR_TEXT)
            drawLabel(
                canvas, if (expanded) "共${s.handTotal}张·点此收起" else "共${s.handTotal}张·点此看牌",
                w / 2f, h * 0.72f, 15f, COLOR_TEXT
            )
            return
        }
        fillBox(canvas, 0f, 0f, w, h, COLOR_FRAME)
        var y = 4f
        if (s.handUnconfirmed) {
            fillBox(canvas, 0f, 0f, w, 36f, COLOR_ALERT)
            drawLabel(canvas, "手牌未确认", w / 2f, 18f, 22f, COLOR_TEXT)
            y = 42f
        }
        val message = s.message ?: return
        val color = if (s.mode == HandPanelMode.SKIPPED) COLOR_EDITED else COLOR_RED
        paint.textSize = 17f
        wrap(message, w - 12f).forEach { line ->
            drawLabel(canvas, line, w / 2f, y + LINE_HEIGHT / 2f, 17f, color)
            y += LINE_HEIGHT
        }
    }

    private fun wrap(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxWidth, null).coerceAtLeast(1)
            lines += text.substring(start, start + count)
            start += count
        }
        return lines
    }

    private fun fillBox(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRect(RectF(left, top, left + width, top + height), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = COLOR_GRID
        canvas.drawRect(RectF(left, top, left + width, top + height), paint)
    }

    private fun drawLabel(canvas: Canvas, text: String, centerX: Float, centerY: Float, size: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        val metrics = paint.fontMetrics
        canvas.drawText(text, centerX, centerY - (metrics.bottom + metrics.top) / 2f, paint)
    }

    private fun drawCell(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        text: String,
        size: Float,
        textColor: Int,
        backgroundColor: Int
    ) {
        fillBox(canvas, left, top, width, height, backgroundColor)
        drawLabel(canvas, text, left + width / 2f, top + height / 2f, size, textColor)
    }

    private companion object {
        const val ROW_HEIGHT = 60f
        const val HEADER_HEIGHT = 24f
        const val LINE_HEIGHT = 22f
        val COLOR_FRAME = Color.rgb(9, 10, 11)
        val COLOR_HEADER = Color.rgb(59, 62, 64)
        val COLOR_VALUE = Color.rgb(53, 56, 58)
        val COLOR_GRID = Color.rgb(24, 26, 28)
        val COLOR_TEXT = Color.rgb(245, 245, 245)
        val COLOR_DARK = Color.rgb(20, 20, 20)
        val COLOR_ZERO = Color.rgb(111, 114, 117)
        val COLOR_RED = Color.rgb(255, 51, 74)
        val COLOR_ALERT = Color.rgb(176, 32, 48)
        val COLOR_EDITED = Color.rgb(255, 229, 154)
        val COLOR_OK = Color.rgb(46, 125, 50)
        val COLOR_DISABLED = Color.rgb(70, 72, 74)
    }
}
