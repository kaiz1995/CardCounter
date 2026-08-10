package com.example.cardcounter.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.capture.hasVisibleCardArea
import com.example.cardcounter.capture.isPlayVisible
import com.example.cardcounter.capture.isPublicRankBand
import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetectorSmokeTest {
    @Test
    fun bothBackendsRunOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            TfliteYoloDetector(context).use { detector ->
                val startedAt = SystemClock.elapsedRealtime()
                val detections = detector.detect(bitmap)
                Log.i(TAG, "TFLITE_OK ms=${SystemClock.elapsedRealtime() - startedAt} detections=${detections.size}")
            }
            MnnYoloDetector(context).use { detector ->
                val startedAt = SystemClock.elapsedRealtime()
                val detections = detector.detect(bitmap)
                Log.i(TAG, "MNN_CPU_OK ms=${SystemClock.elapsedRealtime() - startedAt} detections=${detections.size}")
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun standardFrameBackendsAgree() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = instrumentation.context.assets.open("pre_inf.png").use(BitmapFactory::decodeStream)
        try {
            val tflite = TfliteYoloDetector(context).use { it.detect(bitmap) }
            val mnn = MnnYoloDetector(context).use { it.detect(bitmap) }
            val tfliteRanks = tflite.groupingBy { it.rank }.eachCount()
            val mnnRanks = mnn.groupingBy { it.rank }.eachCount()
            Log.i(TAG, "STANDARD_TFLITE count=${tflite.size} ranks=$tfliteRanks")
            Log.i(TAG, "STANDARD_MNN count=${mnn.size} ranks=$mnnRanks")
            val expected = mapOf(
                Rank.TWO to 3, Rank.ACE to 7, Rank.KING to 1, Rank.QUEEN to 5,
                Rank.NINE to 2, Rank.EIGHT to 3, Rank.SEVEN to 3,
                Rank.SIX to 3, Rank.FIVE to 3, Rank.FOUR to 1
            )
            assertEquals(expected, tfliteRanks)
            assertEquals(expected, mnnRanks)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun rightBottomPairIsDetectedAsTwoAces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets.open("current_a_right_bottom.png").use(BitmapFactory::decodeStream)
        val bounds = PlayZone.RIGHT_BOTTOM.yoloCrop(1_280, 578)
        val crop = canonicalCrop(source, bounds.left, bounds.top, bounds.size, bounds.size)
        try {
            val ranks = MnnYoloDetector(context).use { detector ->
                detector.detect(crop).filter { isPublicRankBand(it.centerY) }
                    .groupingBy { it.rank }.eachCount()
            }
            Log.i(TAG, "RIGHT_BOTTOM_PAIR ranks=$ranks")
            assertEquals(mapOf(Rank.ACE to 2), ranks)
        } finally {
            crop.recycle()
            source.recycle()
        }
    }

    @Test
    fun leftBottomFourSevensUseProductionCrop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets.open("current_left_bottom_7x4.png").use(BitmapFactory::decodeStream)
        val bounds = PlayZone.LEFT_BOTTOM.yoloCrop(1_280, 578)
        val crop = canonicalCrop(source, bounds.left, bounds.top, bounds.size, bounds.size)
        try {
            val ranks = MnnYoloDetector(context).use { detector ->
                detector.detect(crop).filter { isPublicRankBand(it.centerY) }
                    .groupingBy { it.rank }.eachCount()
            }
            Log.i(TAG, "LEFT_BOTTOM_7X4 ranks=$ranks")
            assertEquals(mapOf(Rank.SEVEN to 4), ranks)
        } finally {
            crop.recycle()
            source.recycle()
        }
    }

    @Test
    fun passMarkerFalseSevenIsRejectedButSixPairAccepted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets.open("current_pass_and_sixes.png")
            .use(BitmapFactory::decodeStream)
        val passBounds = PlayZone.RIGHT_TOP.yoloCrop(1_280, 578)
        val sixBounds = PlayZone.RIGHT_BOTTOM.yoloCrop(1_280, 578)
        val passCrop = canonicalCrop(source, passBounds.left, passBounds.top, passBounds.size, passBounds.size)
        val sixCrop = canonicalCrop(source, sixBounds.left, sixBounds.top, sixBounds.size, sixBounds.size)
        try {
            val results = MnnYoloDetector(context).use { detector ->
                listOf(passCrop, sixCrop).map { crop ->
                    detector.detect(crop).filter { isPublicRankBand(it.centerY) }
                        .groupingBy { it.rank }.eachCount()
                }
            }
            assertTrue(results[0].isEmpty())
            assertFalse(isPlayVisible(canonicalWhiteVisible(source, PlayZone.RIGHT_TOP)))
            assertEquals(mapOf(Rank.SIX to 2), results[1])
            assertTrue(isPlayVisible(canonicalWhiteVisible(source, PlayZone.RIGHT_BOTTOM)))
        } finally {
            passCrop.recycle()
            sixCrop.recycle()
            source.recycle()
        }
    }

    @Test
    fun rightTopSevenPairNmsDiagnostics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets.open("current_right_top_7x2_overcount.png")
            .use(BitmapFactory::decodeStream)
        val bounds = PlayZone.RIGHT_TOP.yoloCrop(1_280, 578)
        val crop = canonicalCrop(source, bounds.left, bounds.top, bounds.size, bounds.size)
        try {
            MnnYoloDetector(context).use { detector ->
                val ranks = detector.detect(crop).filter { isPublicRankBand(it.centerY) }
                    .groupingBy { it.rank }.eachCount()
                assertEquals(mapOf(Rank.SEVEN to 2), ranks)
            }
        } finally {
            crop.recycle()
            source.recycle()
        }
    }

    private fun canonicalWhiteVisible(source: Bitmap, zone: PlayZone): Boolean {
        var white = 0
        var samples = 0
        for (y in zone.rankY(578)..(zone.rankY(578) + zone.visibilityHeight) step 8) {
            for (x in zone.left(1_280)..zone.right(1_280) step 8) {
                val color = source.getPixel(x * source.width / 1_280, y * source.height / 578)
                if (Color.red(color) >= 190 && Color.green(color) >= 190 && Color.blue(color) >= 190) white++
                samples++
            }
        }
        return hasVisibleCardArea(white, samples)
    }

    private fun canonicalCrop(
        source: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ): Bitmap {
        val pixels = IntArray(width * height) { index ->
            val x = left + index % width
            val y = top + index / width
            source.getPixel(x * source.width / 1_280, y * source.height / 578)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TAG = "DetectorSmokeTest"
    }
}
