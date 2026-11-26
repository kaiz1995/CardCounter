package com.example.cardcounter.eval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 回归（手机默认 15/6 采样参数、每 150ms 一行观察）：
 * 1) 没停稳够 15 次、清桌后才交出的一手，按最后一帧证据时间对齐真值，
 *    不因清桌等待 6 次采样而掉出时间窗，被判成“漏记 + 多记”；
 * 2) 无共识重试次数在回放结束后统计，不再恒为 0。
 */
class EvalReplayRegressionTest {
    private val gameId = "g000002-1"
    private val two = """{"rank":"TWO","conf":0.9,"cx":0.5,"cy":0.42,"w":0.12,"h":0.22}"""

    private fun zone(name: String, visible: Boolean, dets: String?): String {
        val detsPart = if (dets == null) "" else ",\"dets\":[$dets]"
        return "\"$name\":{\"card_visible\":$visible,\"still\":true$detsPart}"
    }

    @Test
    fun clearFinalizedPlayMatchesTruthAndRetryIsCounted() {
        val root = Files.createTempDirectory("eval-regression")
        try {
            Files.writeString(
                root.resolve("games.json"),
                listOf(
                    """{"schemaVersion":1,"games":[{"gameId":"$gameId","video":"source/y.mp4",""",
                    """"split":"dev","startMs":0,"endMs":5000,"prePlayEndMs":0,"firstPlayMs":0,""",
                    """"complete":true,"selfHand":"TWO:2;KING:1","selfHandCount":3,""",
                    """"review":"AUTO","notes":""}]}"""
                ).joinToString("\n"),
                StandardCharsets.UTF_8
            )
            val truthDir = root.resolve("truth")
            val obsDir = root.resolve("obs")
            Files.createDirectories(truthDir)
            Files.createDirectories(obsDir)
            Files.writeString(
                truthDir.resolve("$gameId.csv"),
                listOf(TruthCsv.HEADER, "$gameId,1,TOP,1000,1000,1750,2,TWO:2,NORMAL,CONFIRMED,")
                    .joinToString("\n", postfix = "\n"),
                StandardCharsets.UTF_8
            )
            // TOP：1000~1600 有牌（只停稳 4 次，不够 15），1750 起清桌，第 6 次空采样在 2500 交出。
            // RIGHT_TOP：1000~1300 有牌但没做识别，清桌后交出无共识 → 记 1 次重试。
            val lines = (0 until 12).map { i ->
                val ts = 1000L + i * 150
                val zones = listOf(
                    if (i <= 4) zone("TOP", true, "$two,$two") else zone("TOP", false, null),
                    zone("RIGHT_TOP", i <= 2, null)
                )
                "{\"ts_ms\":$ts,\"table_visible\":true,\"zones\":{${zones.joinToString(",")}}}"
            }
            Files.writeString(
                obsDir.resolve("$gameId.jsonl"),
                (listOf(ObservationJsonl.metaLine()) + lines).joinToString("\n", postfix = "\n"),
                StandardCharsets.UTF_8
            )
            val out = root.resolve("out")
            val buffer = ByteArrayOutputStream()
            val stream = PrintStream(buffer, true, StandardCharsets.UTF_8)

            val code = OfflineEvalCli.run(
                arrayOf(
                    "--games", root.resolve("games.json").toString(),
                    "--truth-dir", truthDir.toString(),
                    "--obs-dir", obsDir.toString(),
                    "--out", out.toString()
                ),
                stream,
                stream,
                PipelineFactory { LegacyPipeline() }
            )

            val text = buffer.toString(StandardCharsets.UTF_8)
            assertEquals(text, 0, code)
            val json = JSONObject(Files.readString(out.resolve("$gameId.json"), StandardCharsets.UTF_8))
            assertEquals(text, 1, json.getInt("hits"))
            assertEquals(0, json.getInt("misses"))
            assertEquals(0, json.getInt("extras"))
            assertEquals(0, json.getInt("duplicates"))
            assertEquals(1, json.getInt("retryCount"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
