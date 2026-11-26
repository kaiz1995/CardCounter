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
import com.example.cardcounter.capture.HandPanelState
import com.example.cardcounter.domain.HandPanelMode
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.storage.LedgerCommand
import com.example.cardcounter.storage.SessionActor
import com.example.cardcounter.ui.CounterOverlayView
import com.example.cardcounter.ui.HandPanelView
import com.example.cardcounter.ui.OverlayLayout
import kotlin.math.roundToInt

class OverlayService : Service(), HandPanelView.Actions {
    private lateinit var windowManager: WindowManager
    private var counter: CounterOverlayView? = null
    private var hideControl: TextView? = null
    private var resetControl: TextView? = null
    private val handViews = mutableListOf<HandPanelView>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hidden = false

    /** 点点数时是加一张还是减一张；只在主线程读写。 */
    private var addMode = true

    /** 手牌确认后是否展开看牌；只在主线程读写。 */
    private var expanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        hidden = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false)
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachIfNeeded()
        when (intent?.action) {
            ACTION_SHOW -> setHidden(false)
            ACTION_HIDE -> setHidden(true)
        }
        renderAll()
        return START_STICKY
    }

    override fun onDestroy() {
        HandPanelState.onChange = null
        mainHandler.removeCallbacksAndMessages(null)
        (listOfNotNull(counter, hideControl, resetControl) + handViews).forEach { view ->
            runCatching { windowManager.removeView(view) }
        }
        counter = null
        hideControl = null
        resetControl = null
        handViews.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachIfNeeded() {
        if (counter != null) return
        counter = CounterOverlayView(this).also { view ->
            windowManager.addView(view, fullScreenParams())
        }
        hideControl = control(if (hidden) "显示" else "隐藏").also { view ->
            view.setOnClickListener { setHidden(!hidden) }
            windowManager.addView(view, controlParams(OverlayLayout.hideButton))
        }
        resetControl = control("重置").also { view ->
            installResetLongPress(view)
            windowManager.addView(view, controlParams(OverlayLayout.resetButton))
        }
        // 手牌面板是几块单独的可触摸小窗；全屏显示层仍然不接收触摸，不挡游戏操作。
        listOf(
            HandPanelView.Kind.RANKS_LEFT to OverlayLayout.handRanksLeft,
            HandPanelView.Kind.RANKS_RIGHT to OverlayLayout.handRanksRight,
            HandPanelView.Kind.CONTROL to OverlayLayout.handControl,
            HandPanelView.Kind.STATUS to OverlayLayout.handStatus
        ).forEach { (kind, rect) ->
            val view = HandPanelView(this, kind, rect, this)
            windowManager.addView(view, controlParams(rect))
            handViews += view
        }
        HandPanelState.onChange = { mainHandler.post { renderAll() } }
    }

    private fun setHidden(value: Boolean) {
        hidden = value
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDDEN, value).apply()
        hideControl?.text = if (value) "显示" else "隐藏"
        renderAll()
    }

    /** 最近一次画到屏幕上的手牌草稿版本：点“确认手牌”时带上它，草稿换了就不确认。 */
    @Volatile private var renderedDraftVersion = -1L

    /** 按账本状态和手牌草稿重画所有悬浮窗内容，并决定哪几块手牌小窗显示。必须在主线程调用。 */
    private fun renderAll() {
        val state = SessionRepository.get(this).state
        // 草稿只取一次：显示的状态、改过标记和确认时认的版本必须是同一版。
        val draft = HandPanelState.draft.snapshot()
        renderedDraftVersion = draft.version
        val status = HandPanelState.status(state, draft)
        if (status.mode != HandPanelMode.CONFIRMED) expanded = false
        counter?.render(state, status.handUnconfirmed)
        counter?.visibility = if (hidden) View.GONE else View.VISIBLE
        resetControl?.visibility = if (hidden) View.GONE else View.VISIBLE
        val edited = draft.edited
        handViews.forEach { view ->
            if (view.kind == HandPanelView.Kind.STATUS) {
                // 手牌确认后状态窗缩成小条，不再占着整块触摸区挡游戏操作。
                val target = if (status.mode == HandPanelMode.CONFIRMED) {
                    OverlayLayout.handConfirmedChip
                } else {
                    OverlayLayout.handStatus
                }
                if (view.rect != target) {
                    view.rect = target
                    windowManager.updateViewLayout(view, controlParams(target))
                }
            }
            view.render(status, edited, addMode, expanded)
            val shown = when (view.kind) {
                HandPanelView.Kind.RANKS_LEFT, HandPanelView.Kind.RANKS_RIGHT -> when (status.mode) {
                    HandPanelMode.EDITING, HandPanelMode.PAUSED -> true
                    HandPanelMode.CONFIRMED -> expanded
                    HandPanelMode.SKIPPED -> false
                }
                HandPanelView.Kind.CONTROL -> status.mode == HandPanelMode.EDITING || status.mode == HandPanelMode.PAUSED
                HandPanelView.Kind.STATUS -> true
            }
            view.visibility = if (!hidden && shown) View.VISIBLE else View.GONE
        }
    }

    override fun adjust(rank: Rank, delta: Int) {
        HandPanelState.draft.adjust(rank, delta)
        HandPanelState.rejectReason = null
        renderAll()
    }

    override fun toggleAddMode() {
        addMode = !addMode
        renderAll()
    }

    override fun confirm() {
        CaptureProbeService.confirmHand(this, renderedDraftVersion)
    }

    override fun toggleExpanded() {
        expanded = !expanded
        renderAll()
    }

    override fun undoAndResume() {
        if (CaptureProbeService.revertLastAndResume(this)) return
        // 截屏服务没在运行时，由独立单写者安全兜底执行撤销并恢复。
        val repository = SessionRepository.get(this)
        val actor = SessionActor(repository)
        try {
            repository.state.lastUnrevertedOutsidePlay()?.let { lastPlay ->
                actor.post(LedgerCommand.Revert(lastPlay.id))
            }
            actor.post(LedgerCommand.Resume)
        } finally {
            actor.shutdown()
        }
        renderAll()
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
        private const val PREFERENCES = "card_counter_overlay"
        private const val KEY_HIDDEN = "overlay_hidden"
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
