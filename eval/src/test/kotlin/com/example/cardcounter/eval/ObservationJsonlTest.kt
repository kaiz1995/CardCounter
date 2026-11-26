package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationJsonlTest {
    @Test
    fun parsesFramesAndKeepsMissingDetsAsNull() {
        val text = """
            {"ts_ms":100,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"dets":[{"rank":"FOUR","conf":0.89,"cx":0.5,"cy":0.42,"w":0.12,"h":0.22}]}}}

            {"ts_ms":200,"table_visible":false,"zones":{}}
        """.trimIndent()

        val frames = ObservationJsonl.parse(text)

        assertEquals(2, frames.size)
        val top = frames[0].zones.getValue(PlayZone.TOP)
        assertTrue(top.cardVisible)
        assertTrue(top.still)
        val detection = top.detections!!.single()
        assertEquals(Rank.FOUR, detection.rank)
        assertEquals(0.89f, detection.confidence, 1e-6f)
        assertEquals(0.42f, detection.cy, 1e-6f)
        assertFalse(frames[1].tableVisible)
        assertNull(frames[1].zones[PlayZone.TOP])
    }

    @Test
    fun turnBadgeIsOptionalAndDefaultsToFalse() {
        val frame = ObservationJsonl.parseLine(
            """{"ts_ms":1,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true},"LEFT_TOP":{"card_visible":false,"still":true,"turn":true},"RIGHT_TOP":{"card_visible":true,"still":true,"turn":false,"dets":[]}}}"""
        )

        assertFalse(frame.zones.getValue(PlayZone.TOP).turnBadge)
        assertTrue(frame.zones.getValue(PlayZone.LEFT_TOP).turnBadge)
        assertFalse(frame.zones.getValue(PlayZone.RIGHT_TOP).turnBadge)
    }

    @Test
    fun reportsLineAndReasonForNonBooleanTurn() {
        val text = """{"ts_ms":1,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"turn":"yes"}}}"""

        val error = runCatching { ObservationJsonl.parse(text) }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        assertTrue(error!!.message.orEmpty().contains("turn"))
    }

    @Test
    fun zoneWithoutDetsFieldMeansNoDetection() {
        val frame = ObservationJsonl.parseLine(
            """{"ts_ms":1,"table_visible":true,"zones":{"SELF":{"card_visible":true,"still":false}}}"""
        )

        assertNull(frame.zones.getValue(PlayZone.SELF).detections)
        assertFalse(frame.zones.getValue(PlayZone.SELF).still)
    }

    @Test
    fun reportsLineAndReasonForBadRank() {
        val text = """
            {"ts_ms":1,"table_visible":true,"zones":{}}
            {"ts_ms":2,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"dets":[{"rank":"SEVEN?","conf":0.5,"cx":0.5,"cy":0.5,"w":0.1,"h":0.1}]}}}
        """.trimIndent()

        val error = runCatching { ObservationJsonl.parse(text) }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        val message = error!!.message.orEmpty()
        assertTrue(message.contains("第 2 行"))
        assertTrue(message.contains("rank"))
    }

    @Test
    fun reportsLineAndReasonForOutOfRangeCoordinate() {
        val text = """{"ts_ms":1,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"dets":[{"rank":"ACE","conf":0.5,"cx":1.4,"cy":0.5,"w":0.1,"h":0.1}]}}}"""

        val error = runCatching { ObservationJsonl.parse(text) }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        assertTrue(error!!.message.orEmpty().contains("cx"))
    }

    @Test
    fun reportsMissingTimestamp() {
        val text = """{"table_visible":true,"zones":{}}"""

        val error = runCatching { ObservationJsonl.parse(text) }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        assertTrue(error!!.message.orEmpty().contains("ts_ms"))
    }

    @Test
    fun parsesOptionalBase64FingerprintAndRejectsWrongLength() {
        val rawBytes = ByteArray(com.example.cardcounter.capture.RoiFingerprint.COLUMNS * com.example.cardcounter.capture.RoiFingerprint.ROWS * 3) {
            (it % 251).toByte()
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(rawBytes)
        val okFrame = ObservationJsonl.parseLine(
            """{"ts_ms":10,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"fp":"$b64"}}}"""
        )
        val parsedFp = okFrame.zones.getValue(PlayZone.TOP).fingerprint
        assertEquals(0x000102, parsedFp!![0])
        assertEquals(640, parsedFp.size)

        val badB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(12))
        val badError = runCatching {
            ObservationJsonl.parse(
                """{"ts_ms":10,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"fp":"$badB64"}}}"""
            )
        }.exceptionOrNull()
        assertTrue(badError is DataFormatException)
        assertTrue(badError!!.message.orEmpty().contains("fp"))
    }
}
