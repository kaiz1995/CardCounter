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
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.cardcounter.capture.CaptureProbeService
import com.example.cardcounter.capture.CaptureProbeStatus
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.service.OverlayService

class MainActivity : Activity() {
    private lateinit var repository: SessionRepository
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SessionRepository.get(this)
        requestNotificationPermissionIfNeeded()
        setContentView(content())
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            repository = SessionRepository.get(this)
            renderStatus()
        }
    }

    @Deprecated("使用 onActivityResult 保持首版依赖最小")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureProbeService.start(this, resultCode, data)
            status.text = "已请求截屏探针：请切回游戏，通知栏会显示实际帧尺寸和帧率。"
        } else {
            status.text = "未授予截屏权限。"
        }
    }

    private fun content(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(32), dp(24), dp(24))
        setBackgroundColor(Color.rgb(245, 245, 245))

        addView(TextView(context).apply {
            text = "多乐够级记牌器"
            textSize = 24f
            setTextColor(Color.rgb(30, 30, 30))
        })

        status = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(24), 0, dp(24))
        }
        addView(status)

        addView(button("显示记牌框") {
            if (Settings.canDrawOverlays(this@MainActivity)) {
                OverlayService.show(this@MainActivity)
                renderStatus()
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        addView(button("开始截屏探针") {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                status.text = "请先授予浮窗权限。"
                return@button
            }
            OverlayService.show(this@MainActivity)
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQUEST_CAPTURE
            )
        })

        addView(button("新局重置") {
            CaptureProbeService.requestNewGame(this@MainActivity)
            status.text = "正在识别自己手牌；成功后才会开始新局。"
        })
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun renderStatus() {
        val state = repository.state
        val sessionStatus = when (state.phase) {
            SessionPhase.ACTIVE -> if (state.handConfirmed) "本局记牌中。" else "手牌未确认。"
            SessionPhase.PAUSED -> "本局停记：${state.pauseReason?.displayName ?: "未知原因"}"
            SessionPhase.CLOSED -> "会话已结束。"
        }
        status.text = "$sessionStatus\n截屏探针：${CaptureProbeStatus.snapshot().summary()}"
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
    }
}
