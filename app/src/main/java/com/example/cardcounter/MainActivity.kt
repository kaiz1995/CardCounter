package com.example.cardcounter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.cardcounter.capture.CaptureProbeService
import com.example.cardcounter.capture.CaptureProbeStatus
import com.example.cardcounter.capture.OverlayProbeState
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.service.OverlayService
import com.example.cardcounter.storage.LedgerCommand
import com.example.cardcounter.storage.SessionActor

class MainActivity : Activity() {
    private lateinit var repository: SessionRepository
    private lateinit var status: TextView
    private lateinit var diagnosticsToggle: TextView
    private lateinit var diagnosticsText: TextView
    private var diagnosticsExpanded = false
    private var pendingStartAfterOverlayPermission = false
    private var pendingStatusNote: String? = null
    private var activeStatusNote: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            if (::status.isInitialized) {
                renderStatus(activeStatusNote)
            }
            mainHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SessionRepository.get(this)
        requestNotificationPermissionIfNeeded()
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(Color.rgb(245, 245, 245))
                addView(content())
            }
        )
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        OverlayService.setMainForeground(this, true)
        if (::status.isInitialized) {
            repository = SessionRepository.get(this)
            if (pendingStartAfterOverlayPermission) {
                pendingStartAfterOverlayPermission = false
                if (Settings.canDrawOverlays(this)) {
                    launchOverlayAndCapture()
                } else {
                    activeStatusNote = "未授予悬浮窗权限。"
                    renderStatus(activeStatusNote)
                }
            } else {
                pendingStatusNote?.let {
                    activeStatusNote = it
                    pendingStatusNote = null
                }
                renderStatus(activeStatusNote)
            }
            startStatusRefresh()
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(statusRefreshRunnable)
        activeStatusNote = null
        OverlayService.setMainForeground(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statusRefreshRunnable)
        super.onDestroy()
    }

    @Deprecated("使用 onActivityResult 保持首版依赖最小")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            OverlayService.show(this)
            CaptureProbeService.start(this, resultCode, data)
            pendingStatusNote = "已开始记牌，请切回游戏。"
        } else {
            pendingStatusNote = "未授予截屏权限。"
        }
    }

    private fun content(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(24))

        addView(TextView(context).apply {
            text = "多乐够级记牌器"
            textSize = 24f
            setTextColor(Color.rgb(30, 30, 30))
        })

        status = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(18))
        }
        addView(status)

        addView(button("开始记牌") {
            activeStatusNote = null
            if (Settings.canDrawOverlays(this@MainActivity)) {
                launchOverlayAndCapture()
            } else {
                pendingStartAfterOverlayPermission = true
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        addView(button("开新局") {
            activeStatusNote = if (CaptureProbeService.requestNewGame(this@MainActivity)) {
                "已开新局，切回游戏后自动识别手牌。"
            } else {
                resetLedgerOffline()
                "已开新局。"
            }
            renderStatus(activeStatusNote)
        })

        addView(button("停止记牌") {
            stopService(Intent(this@MainActivity, CaptureProbeService::class.java))
            stopService(Intent(this@MainActivity, OverlayService::class.java))
            OverlayProbeState.reset()
            CaptureProbeStatus.stopped()
            activeStatusNote = "已停止记牌。"
            renderStatus(activeStatusNote)
        })

        diagnosticsToggle = TextView(context).apply {
            text = "▶ 运行诊断"
            textSize = 14f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, dp(10), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                diagnosticsExpanded = !diagnosticsExpanded
                updateDiagnosticsVisibility()
            }
        }
        addView(diagnosticsToggle)

        diagnosticsText = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.rgb(110, 110, 110))
            setPadding(dp(8), dp(4), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        addView(diagnosticsText)
    }

    private fun launchOverlayAndCapture() {
        if (!CaptureProbeStatus.snapshot().running) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } else {
            OverlayService.show(this)
            renderStatus(activeStatusNote)
        }
    }

    private fun startStatusRefresh() {
        mainHandler.removeCallbacks(statusRefreshRunnable)
        mainHandler.postDelayed(statusRefreshRunnable, STATUS_REFRESH_INTERVAL_MS)
    }

    private fun resetLedgerOffline() {
        val actor = SessionActor(repository)
        try {
            actor.post(LedgerCommand.NewSession())
        } finally {
            actor.shutdown()
        }
        OverlayProbeState.reset()
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun renderStatus(note: String? = null) {
        val state = repository.state
        val captureRunning = CaptureProbeStatus.snapshot().running
        val probe = OverlayProbeState.snapshot()
        val mainStatus = when {
            !captureRunning -> "状态：未开始"
            state.phase == SessionPhase.PAUSED ->
                "状态：已停记（${state.pauseReason?.displayName ?: "未知原因"}）"
            probe.gameSkipped -> "状态：本局未记（中途打开，等下一局）"
            state.handConfirmed -> "状态：记牌中"
            else -> "状态：手牌识别中"
        }
        status.text = if (note.isNullOrBlank()) mainStatus else "$mainStatus\n$note"
        diagnosticsText.text = "截屏探针：${CaptureProbeStatus.snapshot().summary()}"
        updateDiagnosticsVisibility()
    }

    private fun updateDiagnosticsVisibility() {
        diagnosticsToggle.text = if (diagnosticsExpanded) "▼ 运行诊断" else "▶ 运行诊断"
        diagnosticsText.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val REQUEST_CAPTURE = 2
        const val STATUS_REFRESH_INTERVAL_MS = 250L
    }
}
