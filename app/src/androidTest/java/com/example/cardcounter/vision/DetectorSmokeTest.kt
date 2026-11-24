package com.example.cardcounter.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** 真机上的新模型冒烟：期望值 = 电脑端 eval_crops.SHOT_CASES（逐区肉眼确认）。 */
@RunWith(AndroidJUnit4::class)
class DetectorSmokeTest {
    @Test
    fun modelRunsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            OnnxRankDetector(context).use { detector ->
                val startedAt = SystemClock.elapsedRealtime()
                val detections = detector.detect(bitmap)
                Log.i(TAG, "ONNX_CPU_OK ms=${SystemClock.elapsedRealtime() - startedAt} detections=${detections.size}")
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun screenshotZonesMatchExpectedCounts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        OnnxRankDetector(context).use { detector ->
            SHOT_CASES.forEach { (file, zones) ->
                val shot = instrumentation.context.assets.open(file).use(BitmapFactory::decodeStream)
                val frame = Bitmap.createScaledBitmap(shot, FRAME_W, FRAME_H, true)
                try {
                    PlayZone.entries.forEach { zone ->
                        val input = letterboxedZone(frame, zone)
                        val ranks = try {
                            detector.detect(input).groupingBy { it.rank }.eachCount()
                        } finally {
                            input.recycle()
                        }
                        val expected = zones[zone].orEmpty()
                        Log.i(TAG, "$file $zone ranks=$ranks expected=$expected")
                        assertEquals("$file $zone", expected, ranks)
                    }
                } finally {
                    if (frame !== shot) frame.recycle()
                    shot.recycle()
                }
            }
        }
    }

    /** 与 CaptureProbeService.publicPlayBitmap 同一几何：等比缩放居中贴到 320×320，四周填 114 灰。 */
    private fun letterboxedZone(frame: Bitmap, zone: PlayZone): Bitmap {
        val crop = zone.yoloCrop(FRAME_W, FRAME_H)
        val source = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width, crop.height)
        val scale = minOf(320f / crop.width, 320f / crop.height)
        val scaled = Bitmap.createScaledBitmap(
            source, (crop.width * scale).roundToInt(), (crop.height * scale).roundToInt(), true
        )
        val output = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        output.eraseColor(0xff727272.toInt())
        Canvas(output).drawBitmap(scaled, (320 - scaled.width) / 2f, (320 - scaled.height) / 2f, null)
        if (scaled !== source) source.recycle()
        scaled.recycle()
        return output
    }

    private companion object {
        const val TAG = "DetectorSmokeTest"
        const val FRAME_W = 1_280
        const val FRAME_H = 578

        val SHOT_CASES: Map<String, Map<PlayZone, Map<Rank, Int>>> = mapOf(
            "current_a_right_bottom.png" to mapOf(PlayZone.RIGHT_BOTTOM to mapOf(Rank.ACE to 2)),
            "current_left_bottom_7x4.png" to mapOf(PlayZone.LEFT_BOTTOM to mapOf(Rank.SEVEN to 4)),
            "current_right_top_7x2_overcount.png" to mapOf(
                PlayZone.TOP to mapOf(Rank.NINE to 2),
                PlayZone.RIGHT_TOP to mapOf(Rank.SEVEN to 2),
                PlayZone.RIGHT_BOTTOM to mapOf(Rank.SIX to 2)
            ),
            "current_pass_and_sixes.png" to mapOf(
                PlayZone.TOP to mapOf(Rank.SEVEN to 2),
                PlayZone.LEFT_TOP to mapOf(Rank.TEN to 2),
                PlayZone.LEFT_BOTTOM to mapOf(Rank.QUEEN to 2),
                PlayZone.RIGHT_BOTTOM to mapOf(Rank.SIX to 2)
            )
        )
    }
}
