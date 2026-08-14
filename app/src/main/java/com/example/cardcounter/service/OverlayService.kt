package com.example.cardcounter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.cardcounter.R
import com.example.cardcounter.capture.CaptureProbeService
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.ui.CounterOverlayView
import com.example.cardcounter.ui.OverlayLayout
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var counter: CounterOverlayView? = null
    private var hideControl: TextView? = null
    private var resetControl: TextView? = null
    private var hidden = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachIfNeeded()
        when (intent?.action) {
            ACTION_SHOW -> setHidden(false)
            ACTION_HIDE -> setHidden(true)
        }
        counter?.render(SessionRepository(this).state)
        return START_STICKY
    }

    override fun onDestroy() {
        listOfNotNull(counter, hideControl, resetControl).forEach { view ->
            runCatching { windowManager.removeView(view) }
        }
        counter = null
        hideControl = null
        resetControl = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachIfNeeded() {
        if (counter != null) return
        counter = CounterOverlayView(this).also { view ->
            windowManager.addView(view, fullScreenParams())
        }
        hideControl = control("隐藏").also { view ->
            view.setOnClickListener { setHidden(!hidden) }
            windowManager.addView(view, controlParams(OverlayLayout.hideButton))
        }
        resetControl = control("重置").also { view ->
            installResetLongPress(view)
            windowManager.addView(view, controlParams(OverlayLayout.resetButton))
        }
    }

    private fun setHidden(value: Boolean) {
        hidden = value
        counter?.visibility = if (value) View.GONE else View.VISIBLE
        resetControl?.visibility = if (value) View.GONE else View.VISIBLE
        hideControl?.text = if (value) "显示" else "隐藏"
    }

    private fun installResetLongPress(view: View) {
        val handler = Handler(Looper.getMainLooper())
        val reset = Runnable {
            CaptureProbeService.requestNewGame(this)
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> handler.postDelayed(reset, RESET_HOLD_MS)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(reset)
            }
            true
        }
    }

    private fun control(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(20, 20, 20))
        textSize = 18f
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(255, 229, 154))
            setStroke(dp(2), Color.rgb(35, 35, 35))
        }
    }

    private fun fullScreenParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = 0.79f // Android 12跨应用触摸穿透要求低于默认0.8遮挡阈值。
    }

    private fun controlParams(rect: OverlayLayout.Rect): WindowManager.LayoutParams {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val scaleX = metrics.widthPixels.toFloat() / OverlayLayout.DESIGN_WIDTH
        val scaleY = metrics.heightPixels.toFloat() / OverlayLayout.DESIGN_HEIGHT
        return WindowManager.LayoutParams(
            (rect.width * scaleX).roundToInt(),
            (rect.height * scaleY).roundToInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (rect.x * scaleX).roundToInt()
            y = (rect.y * scaleY).roundToInt()
        }
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_counter)
        .setContentTitle("记牌器运行中")
        .setContentText("座位出牌与外面剩余已显示")
        .setOngoing(true)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "记牌器",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val CHANNEL_ID = "card_counter_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val RESET_HOLD_MS = 800L
        const val ACTION_SHOW = "com.example.cardcounter.SHOW"
        const val ACTION_REFRESH = "com.example.cardcounter.REFRESH"
        const val ACTION_HIDE = "com.example.cardcounter.HIDE"

        fun show(context: Context) = send(context, ACTION_SHOW)
        fun refresh(context: Context) = send(context, ACTION_REFRESH)
        fun hide(context: Context) = send(context, ACTION_HIDE)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, OverlayService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
