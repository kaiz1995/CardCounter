package com.example.cardcounter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect as AndroidRect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.cardcounter.MainActivity
import com.example.cardcounter.R
import com.example.cardcounter.ResumeCaptureActivity
import com.example.cardcounter.capture.CaptureProbeService
import com.example.cardcounter.capture.CaptureProbeStatus
import com.example.cardcounter.capture.OverlayProbeState
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.ui.CounterOverlayView
import com.example.cardcounter.ui.HoldNewGameButton
import com.example.cardcounter.ui.IconTapAction
import com.example.cardcounter.ui.LeaveTableTimer
import com.example.cardcounter.ui.OverlayIconState
import com.example.cardcounter.ui.OverlayLayout
import com.example.cardcounter.ui.OverlayPanelState
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var counter: CounterOverlayView? = null
    private var iconView: TextView? = null
    private var holdNewGameView: HoldNewGameButton? = null
    private var iconMenuView: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 面板展开/收起状态机（纯逻辑，见 OverlayPanelState）。 */
    private val panel = OverlayPanelState()

    /** 离开牌桌后延迟约 3 秒收起，避免牌桌特效一闪就收。 */
    private val leaveTableRunnable = Runnable {
        panel.onLeaveTableTimeout()
        renderAll()
    }

    /** 手牌锁定后 2 秒确认提示到点收起。 */
    private val bannerRefreshRunnable = Runnable { renderAll() }

    /** 小菜单左边的按钮：平时是“打开记牌器”，系统停掉截屏后是“继续记牌”。 */
    private var menuPrimary: TextView? = null

    /** 上一次渲染时是否处于“系统停止截屏”的暂停态，只在翻转时记日志。 */
    private var lastPaused = false

    /** 点击小图标弹出的两项菜单是否可见。 */
    private var menuVisible = false

    /** 竖屏小图标正在被手指拖动：期间 renderAll 不重设小图标位置，防止跳回贴边。 */
    private var iconDragging = false

    /** 竖屏小图标贴哪边和纵坐标（像素），记住在 SharedPreferences。 */
    private var portraitOnRight = true
    private var portraitY = -1

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        portraitOnRight = prefs.getBoolean(KEY_PORTRAIT_ON_RIGHT, true)
        portraitY = prefs.getInt(KEY_PORTRAIT_Y, -1)
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachIfNeeded()
        when (intent?.action) {
            ACTION_SHOW -> menuVisible = false
            ACTION_MAIN_FOREGROUND -> {
                mainForeground = true
                menuVisible = false
            }
            ACTION_MAIN_BACKGROUND -> mainForeground = false
        }
        renderAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        iconDragging = false
        isRunning = false
        OverlayProbeState.onChange = null
        mainHandler.removeCallbacksAndMessages(null)
        listOfNotNull(counter, iconView, holdNewGameView, iconMenuView).forEach { view ->
            runCatching { windowManager.removeView(view) }
        }
        counter = null
        iconView = null
        holdNewGameView = null
        iconMenuView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 横竖屏切换后全屏显示层会跟着变，可触摸小窗按当时屏幕宽高重算。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        menuVisible = false
        iconDragging = false
        iconView?.let { windowManager.updateViewLayout(it, controlParams(iconPixelRect(newConfig))) }
        holdNewGameView?.let { windowManager.updateViewLayout(it, controlParams(OverlayLayout.holdNewGame)) }
        iconMenuView?.let { windowManager.updateViewLayout(it, controlParams(menuPixelRect(newConfig))) }
        renderAll()
    }

    private fun attachIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            isRunning = false
            OverlayProbeState.onChange = null
            stopSelf()
            return
        }
        if (counter != null) return
        counter = CounterOverlayView(this).also { view ->
            windowManager.addView(view, fullScreenParams())
        }
        iconView = createIconView().also { view ->
            installIconTouch(view)
            windowManager.addView(view, controlParams(iconPixelRect(resources.configuration)))
        }
        holdNewGameView = HoldNewGameButton(
            context = this,
            isCaptureRunning = { CaptureProbeStatus.snapshot().running },
            onConfirmedNewGame = { CaptureProbeService.requestNewGame(this) }
        ).also { view ->
            windowManager.addView(view, controlParams(OverlayLayout.holdNewGame))
        }
        iconMenuView = createIconMenuView().also { view ->
            windowManager.addView(view, controlParams(menuPixelRect(resources.configuration)))
        }
        OverlayProbeState.onChange = { mainHandler.post { renderAll() } }
    }

    private fun renderAll() {
        val counterView = counter ?: return
        val icon = iconView ?: return
        val holdButton = holdNewGameView ?: return
        val menu = iconMenuView ?: return

        val config = resources.configuration
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val captureRunning = CaptureProbeStatus.snapshot().running
        val probe = OverlayProbeState.snapshot()
        val state = SessionRepository.get(this).state

        val capture = CaptureProbeStatus.snapshot()
        val pausedBySystem = capture.pausedBySystem && !capture.running
        if (pausedBySystem != lastPaused) {
            lastPaused = pausedBySystem
            Log.i(
                TAG,
                if (pausedBySystem) "OVERLAY_PAUSED reason=system_stop" else "OVERLAY_RESUMED captureRunning=${capture.running}"
            )
        }
        when (panel.update(mainForeground, isLandscape, captureRunning, probe.tableVisible)) {
            LeaveTableTimer.START -> mainHandler.postDelayed(leaveTableRunnable, LEAVE_TABLE_DELAY_MS)
            LeaveTableTimer.CANCEL -> mainHandler.removeCallbacks(leaveTableRunnable)
            LeaveTableTimer.NONE -> Unit
        }
        menuPrimary?.apply {
            text = if (pausedBySystem) "继续记牌" else "打开记牌器"
            setTextColor(if (pausedBySystem) Color.rgb(96, 200, 132) else Color.WHITE)
        }

        mainHandler.removeCallbacks(bannerRefreshRunnable)
        val confirmedAt = probe.handConfirmedAtMs
        val remainingBannerMs = if (confirmedAt != null && state.handConfirmed) {
            (CONFIRMED_BANNER_MS - (SystemClock.elapsedRealtime() - confirmedAt)).coerceAtLeast(0L)
        } else {
            0L
        }
        val showConfirmedBanner = remainingBannerMs > 0L
        if (showConfirmedBanner) {
            mainHandler.postDelayed(bannerRefreshRunnable, remainingBannerMs + 20L)
        }

        if (mainForeground) {
            menuVisible = false
            counterView.visibility = View.GONE
            icon.visibility = View.GONE
            holdButton.visibility = View.GONE
            menu.visibility = View.GONE
            return
        }

        if (!isLandscape) {
            counterView.visibility = View.GONE
            holdButton.visibility = View.GONE
            icon.visibility = View.VISIBLE
            updateIconAppearance(icon, panel.iconState(false, captureRunning, pausedBySystem))
            if (!iconDragging) {
                windowManager.updateViewLayout(icon, controlParams(iconPixelRect(config)))
            }
            menu.visibility = if (menuVisible) View.VISIBLE else View.GONE
            if (menuVisible) {
                windowManager.updateViewLayout(menu, controlParams(menuPixelRect(config)))
            }
            return
        }

        val showExpanded = panel.showExpanded()
        counterView.render(state, probe, showConfirmedBanner)
        counterView.visibility = if (showExpanded) View.VISIBLE else View.GONE
        holdButton.visibility = if (showExpanded) View.VISIBLE else View.GONE
        icon.visibility = View.VISIBLE
        updateIconAppearance(icon, panel.iconState(true, captureRunning, pausedBySystem))
        if (!iconDragging) {
            windowManager.updateViewLayout(icon, controlParams(iconPixelRect(config)))
        }
        menu.visibility = if (menuVisible) View.VISIBLE else View.GONE
        if (menuVisible) {
            windowManager.updateViewLayout(menu, controlParams(menuPixelRect(config)))
        }
    }

    private fun createIconView(): TextView = TextView(this).apply {
        text = "记"
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(228, 30, 34, 38))
            setStroke(dp(2), Color.rgb(212, 175, 95))
        }
    }

    /** 描边色区分四种状态：展开=绿，自己手动收起=金，自动收起=蓝，系统停止截屏后暂停=红。 */
    private fun updateIconAppearance(icon: TextView, state: OverlayIconState) {
        val drawable = icon.background as? GradientDrawable ?: return
        val (fill, stroke) = when (state) {
            OverlayIconState.EXPANDED -> Color.argb(228, 42, 62, 48) to Color.rgb(96, 200, 132)
            OverlayIconState.COLLAPSED -> Color.argb(228, 30, 34, 38) to Color.rgb(212, 175, 95)
            OverlayIconState.COLLAPSED_AUTO -> Color.argb(228, 30, 40, 52) to Color.rgb(110, 170, 230)
            OverlayIconState.PAUSED -> Color.argb(228, 58, 30, 32) to Color.rgb(230, 90, 90)
        }
        drawable.setColor(fill)
        drawable.setStroke(dp(2), stroke)
    }

    private fun createIconMenuView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(242, 24, 27, 30))
            setStroke(dp(1), Color.rgb(90, 96, 102))
        }
        addView(menuButton("打开记牌器", Color.WHITE) {
            menuVisible = false
            renderAll()
            if (isPausedBySystem()) {
                resumeCapture()
            } else {
                val intent = Intent(this@OverlayService, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                startActivity(intent)
            }
        }.also { menuPrimary = it })
        addView(View(context).apply {
            setBackgroundColor(Color.rgb(65, 70, 76))
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        })
        addView(menuButton("停止记牌", Color.rgb(255, 110, 120)) {
            menuVisible = false
            stop(this@OverlayService)
            stopService(Intent(this@OverlayService, CaptureProbeService::class.java))
        })
    }

    private fun menuButton(label: String, textColor: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 14f
        setTextColor(textColor)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun installIconTouch(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var longPressed = false
        val longPressRunnable = Runnable {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                longPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                menuVisible = !menuVisible
                renderAll()
            }
        }

        view.setOnTouchListener { _, event ->
            val config = resources.configuration
            val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            val screen = screenSize()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val currentRect = iconPixelRect(config)
                    startX = currentRect.left
                    startY = currentRect.top
                    iconDragging = false
                    longPressed = false
                    if (isLandscape) {
                        mainHandler.postDelayed(
                            longPressRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (!isLandscape) {
                            if (!iconDragging) {
                                iconDragging = true
                                if (menuVisible) {
                                    menuVisible = false
                                    iconMenuView?.visibility = View.GONE
                                }
                            }
                            val size = dp(OverlayLayout.ICON_DP)
                            val maxX = (screen.first - size).coerceAtLeast(0)
                            val maxY = (screen.second - size).coerceAtLeast(0)
                            val nextX = (startX + dx.roundToInt()).coerceIn(0, maxX)
                            val nextY = (startY + dy.roundToInt()).coerceIn(0, maxY)
                            windowManager.updateViewLayout(
                                view,
                                controlParams(AndroidRect(nextX, nextY, nextX + size, nextY + size))
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isLandscape && iconDragging) {
                        iconDragging = false
                        val size = dp(OverlayLayout.ICON_DP)
                        val maxX = (screen.first - size).coerceAtLeast(0)
                        val maxY = (screen.second - size).coerceAtLeast(0)
                        val nextX = (startX + (event.rawX - downRawX).roundToInt()).coerceIn(0, maxX)
                        val nextY = (startY + (event.rawY - downRawY).roundToInt()).coerceIn(0, maxY)
                        portraitOnRight = nextX + size / 2 >= screen.first / 2
                        portraitY = nextY
                        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                            .putBoolean(KEY_PORTRAIT_ON_RIGHT, portraitOnRight)
                            .putInt(KEY_PORTRAIT_Y, portraitY)
                            .apply()
                        renderAll()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                        iconDragging = false
                        onIconTap(isLandscape)
                    } else {
                        iconDragging = false
                    }
                    longPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onIconTap(isLandscape: Boolean) {
        when (panel.onIconTap(isLandscape, CaptureProbeStatus.snapshot().running)) {
            IconTapAction.PANEL_CHANGED -> menuVisible = false
            IconTapAction.TOGGLE_MENU -> menuVisible = !menuVisible
        }
        renderAll()
    }

    private fun isPausedBySystem(): Boolean =
        CaptureProbeStatus.snapshot().let { it.pausedBySystem && !it.running }

    /** 从悬浮窗打开透明授权页补发截屏授权，用户留在游戏里。 */
    private fun resumeCapture() {
        Log.i(TAG, "OVERLAY_RESUME_REQUESTED")
        ResumeCaptureActivity.launch(this)
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    private fun scaleRect(rect: OverlayLayout.Rect): AndroidRect {
        val (screenW, screenH) = screenSize()
        val scaleX = screenW.toFloat() / OverlayLayout.DESIGN_WIDTH
        val scaleY = screenH.toFloat() / OverlayLayout.DESIGN_HEIGHT
        val left = (rect.x * scaleX).roundToInt()
        val top = (rect.y * scaleY).roundToInt()
        val width = (rect.width * scaleX).roundToInt().coerceAtLeast(1)
        val height = (rect.height * scaleY).roundToInt().coerceAtLeast(1)
        return AndroidRect(left, top, left + width, top + height)
    }

    private fun iconPixelRect(config: Configuration): AndroidRect {
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val box = scaleRect(OverlayLayout.iconLandscape)
            val side = minOf(box.width(), box.height(), dp(OverlayLayout.ICON_DP)).coerceAtLeast(1)
            val left = box.left + (box.width() - side) / 2
            val top = box.top + (box.height() - side) / 2
            return AndroidRect(left, top, left + side, top + side)
        }
        val (screenW, screenH) = screenSize()
        val size = dp(OverlayLayout.ICON_DP)
        val margin = dp(PORTRAIT_EDGE_MARGIN_DP)
        val left = if (portraitOnRight) {
            (screenW - size - margin).coerceAtLeast(0)
        } else {
            margin.coerceAtMost((screenW - size).coerceAtLeast(0))
        }
        val minY = dp(PORTRAIT_VERTICAL_MARGIN_DP)
        val maxY = (screenH - size - dp(PORTRAIT_VERTICAL_MARGIN_DP)).coerceAtLeast(minY)
        val rawY = if (portraitY >= 0) portraitY else screenH / 3
        val top = rawY.coerceIn(minY, maxY)
        return AndroidRect(left, top, left + size, top + size)
    }

    private fun menuPixelRect(config: Configuration): AndroidRect {
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return scaleRect(OverlayLayout.iconMenu)
        }
        val (screenW, screenH) = screenSize()
        val icon = iconPixelRect(config)
        val menuW = dp(PORTRAIT_MENU_WIDTH_DP).coerceAtMost((screenW - dp(16)).coerceAtLeast(1))
        val menuH = dp(OverlayLayout.ICON_DP)
        val gap = dp(8)
        val left = if (portraitOnRight) {
            (icon.left - gap - menuW).coerceAtLeast(dp(8))
        } else {
            (icon.right + gap).coerceAtMost((screenW - menuW - dp(8)).coerceAtLeast(0))
        }
        val top = icon.top.coerceIn(dp(8), (screenH - menuH - dp(8)).coerceAtLeast(dp(8)))
        return AndroidRect(left, top, left + menuW, top + menuH)
    }

    private fun fullScreenParams(): WindowManager.LayoutParams = overlayParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    ).apply {
        alpha = 0.79f // Android 12跨应用触摸穿透要求低于默认0.8遮挡阈值。
    }

    private fun controlParams(rect: OverlayLayout.Rect): WindowManager.LayoutParams =
        controlParams(scaleRect(rect))

    private fun controlParams(pixelRect: AndroidRect): WindowManager.LayoutParams = overlayParams(
        pixelRect.width(),
        pixelRect.height(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    ).apply {
        x = pixelRect.left
        y = pixelRect.top
    }

    /**
     * 所有悬浮窗都从这里建：坐标按整块屏幕（和截屏同一套 1280×578 设计坐标）摆放。
     * 必须铺进刘海区：否则横屏时系统把窗口挤到刘海右边，全屏显示层整体右移并被压窄。
     */
    private fun overlayParams(width: Int, height: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            overlayType(),
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 截屏坐标原点永远在物理左上角；START 在从右到左的系统语言下会变成右边。
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                // 默认会给可见的状态栏、导航栏让位，窗口被挤开压窄，和截屏错位。
                fitInsetsTypes = 0
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
        .setContentText("已按当前场景自动显示记牌器")
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
        private const val LEAVE_TABLE_DELAY_MS = 3_000L
        private const val TAG = "OverlayService"
        private const val CONFIRMED_BANNER_MS = 2_000L
        private const val PORTRAIT_EDGE_MARGIN_DP = 6
        private const val PORTRAIT_VERTICAL_MARGIN_DP = 36
        private const val PORTRAIT_MENU_WIDTH_DP = 208
        private const val PREFERENCES = "card_counter_overlay"
        private const val KEY_PORTRAIT_ON_RIGHT = "portrait_on_right"
        private const val KEY_PORTRAIT_Y = "portrait_y"
        const val ACTION_SHOW = "com.example.cardcounter.SHOW"
        const val ACTION_REFRESH = "com.example.cardcounter.REFRESH"
        const val ACTION_MAIN_FOREGROUND = "com.example.cardcounter.MAIN_FOREGROUND"
        const val ACTION_MAIN_BACKGROUND = "com.example.cardcounter.MAIN_BACKGROUND"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var mainForeground: Boolean = false

        fun show(context: Context) = send(context, ACTION_SHOW)

        /**
         * 停掉悬浮窗服务。先把 [isRunning] 置 false、断开状态回调，再停服务：服务真正销毁前，
         * 识别线程或回调线程调 [refresh] 就不会再发启动请求，把刚停掉的悬浮窗和常驻通知重新拉起来。
         * 所有停悬浮窗的入口（主界面、小图标菜单）都走这里，并清掉“系统停止截屏”的暂停标记；
         * 截屏被系统停止时不走这里：悬浮窗留着并进入暂停态，由用户点“继续记牌”恢复。
         */
        fun stop(context: Context) {
            isRunning = false
            CaptureProbeStatus.clearPause()
            OverlayProbeState.onChange = null
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun refresh(context: Context) {
            if (isRunning) send(context, ACTION_REFRESH)
        }

        fun setMainForeground(context: Context, foreground: Boolean) {
            mainForeground = foreground
            if (isRunning) {
                send(context, if (foreground) ACTION_MAIN_FOREGROUND else ACTION_MAIN_BACKGROUND)
            }
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, OverlayService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
