package com.example.cardcounter.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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
                try {
                    PlayZone.entries.forEach { zone ->
                        val input = letterboxedZone(shot, zone)
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
                    shot.recycle()
                }
            }
        }
    }

    /** 与 CaptureProbeService.publicPlayBitmap 同一套：整屏区域平均缩到 1280×578，再等比缩放居中、四周填 114 灰（ScreenScale）。 */
    private fun letterboxedZone(shot: Bitmap, zone: PlayZone): Bitmap {
        val pixels = IntArray(shot.width * shot.height)
        shot.getPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
        val crop = zone.yoloCrop(FRAME_W, FRAME_H)
        val input = ScreenScale.playZoneInput(ArgbRaster(pixels, shot.width, shot.height), crop.left, crop.top, crop.width, crop.height)
        return Bitmap.createBitmap(input, 320, 320, Bitmap.Config.ARGB_8888)
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
