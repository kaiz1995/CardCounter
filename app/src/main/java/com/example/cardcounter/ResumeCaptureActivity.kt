package com.example.cardcounter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.example.cardcounter.capture.CaptureProbeService
import com.example.cardcounter.capture.CaptureProbeStatus

/**
 * 悬浮窗“继续记牌”打开的透明小页：申请截屏授权，授权成功就启动 [CaptureProbeService] 接着记这一局
 * （账本里已有手牌或外面出牌时按 resumed 处理，不重复记），然后立即关闭，用户留在游戏里；
 * 取消授权也立即关闭，悬浮窗保持“已暂停”。不进最近任务，见 AndroidManifest。
 */
class ResumeCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CaptureProbeStatus.snapshot().running) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }
    }

    @Deprecated("与 MainActivity 一致，沿用 onActivityResult 保持依赖最小")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "OVERLAY_RESUME_GRANTED")
                CaptureProbeService.start(this, resultCode, data)
            } else {
                Log.i(TAG, "OVERLAY_RESUME_CANCELLED")
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST_CAPTURE = 2001
        private const val TAG = "ResumeCapture"

        fun launch(context: Context) {
            context.startActivity(
                Intent(context, ResumeCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
