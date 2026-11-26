package com.example.cardcounter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * “长按开新局”胶囊按钮（只在横屏牌桌上显示）：
 * 按住 0.8 秒进度填满，满后轻震并闪“已开新局”约 1.2 秒；
 * 没按够松手提示“请按住”；截屏没开时闪“未开始记牌”。
 */
class HoldNewGameButton(
    context: Context,
    private val isCaptureRunning: () -> Boolean,
    private val onConfirmedNewGame: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pillPath = Path()
    private val bounds = RectF()

    private var holding = false
    private var triggered = false
    private var holdStartMs = 0L
    private var progress = 0f
    private var flashText: String? = null
    private var flashConfirmed = false

    private val clearFlash = Runnable {
        flashText = null
        flashConfirmed = false
        invalidate()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!holding) return
            val elapsed = SystemClock.uptimeMillis() - holdStartMs
            val next = (elapsed.toFloat() / HOLD_DURATION_MS).coerceIn(0f, 1f)
            if (next >= 1f) {
                holding = false
                triggered = true
                progress = 0f
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onConfirmedNewGame()
                showFlash("已开新局", confirmed = true, durationMs = CONFIRMED_FLASH_MS)
                return
            }
            progress = next
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancelHold()
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        removeCallbacks(clearFlash)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(clearFlash)
                if (!isCaptureRunning()) {
                    cancelHold()
                    showFlash("未开始记牌", confirmed = false, durationMs = CONFIRMED_FLASH_MS)
                    return true
                }
                holding = true
                triggered = false
                holdStartMs = SystemClock.uptimeMillis()
                progress = 0f
                flashText = null
                flashConfirmed = false
                postOnAnimation(tick)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasHolding = holding && !triggered
                cancelHold()
                if (wasHolding) {
                    showFlash("请按住", confirmed = false, durationMs = HINT_FLASH_MS)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val inset = 2f
        bounds.set(inset, inset, width - inset, height - inset)
        val radius = bounds.height() / 2f
        pillPath.reset()
        pillPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(pillPath)

        paint.style = Paint.Style.FILL
        paint.color = when {
            flashConfirmed -> COLOR_CONFIRMED_BG
            flashText != null -> COLOR_HINT_BG
            else -> COLOR_IDLE_BG
        }
        canvas.drawRect(bounds, paint)

        if (holding && progress > 0f) {
            paint.color = COLOR_PROGRESS
            canvas.drawRect(bounds.left, bounds.top, bounds.left + bounds.width() * progress, bounds.bottom, paint)
        }
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = when {
            flashConfirmed -> COLOR_CONFIRMED_BORDER
            holding -> COLOR_PROGRESS
            else -> COLOR_BORDER
        }
        canvas.drawRoundRect(bounds, radius, radius, paint)

        val label = flashText ?: "长按开新局"
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = fitTextSize(label, bounds.width() - 16f, bounds.height() * 0.38f)
        val metrics = paint.fontMetrics
        val baseline = bounds.centerY() - (metrics.bottom + metrics.top) / 2f
        canvas.drawText(label, bounds.centerX(), baseline, paint)
    }

    private fun showFlash(text: String, confirmed: Boolean, durationMs: Long) {
        removeCallbacks(clearFlash)
        flashText = text
        flashConfirmed = confirmed
        postDelayed(clearFlash, durationMs)
        invalidate()
    }

    private fun cancelHold() {
        holding = false
        progress = 0f
        removeCallbacks(tick)
    }

    private fun fitTextSize(text: String, maxWidth: Float, preferredSize: Float): Float {
        var size = preferredSize.coerceAtLeast(10f)
        paint.textSize = size
        val measured = paint.measureText(text)
        if (measured > maxWidth && maxWidth > 0f) {
            size = (size * maxWidth / measured).coerceAtLeast(10f)
        }
        return size
    }

    private companion object {
        const val HOLD_DURATION_MS = 800L
        const val CONFIRMED_FLASH_MS = 1_200L
        const val HINT_FLASH_MS = 1_000L

        val COLOR_IDLE_BG = Color.argb(230, 32, 36, 40)
        val COLOR_BORDER = Color.rgb(212, 175, 95)
        val COLOR_PROGRESS = Color.rgb(214, 148, 36)
        val COLOR_CONFIRMED_BG = Color.rgb(32, 134, 78)
        val COLOR_CONFIRMED_BORDER = Color.rgb(88, 206, 136)
        val COLOR_HINT_BG = Color.rgb(168, 56, 48)
    }
}
