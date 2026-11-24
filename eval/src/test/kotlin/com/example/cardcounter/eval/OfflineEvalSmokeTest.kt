package com.example.cardcounter.eval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 端到端冒烟：手写 5 帧 JSONL + 2 手真值，跑手机现行判定逻辑的离线等价实现。
 * 停稳/清桌采样数调到 1，只为让 5 帧能触发两次最终化；手机默认 15/6 不变。
 */
class OfflineEvalSmokeTest {
    private val gameId = "g000001-1"
    private val fastLegacy = PipelineFactory {
        LegacyPipeline(stableSamples = 1, clearSamples = 1, maxFrames = 8)
    }

    private val header = TruthCsv.HEADER

    private fun det(rank: String, conf: Double = 0.9) =
        """{"rank":"$rank","conf":$conf,"cx":0.5,"cy":0.42,"w":0.12,"h":0.22}"""

    private fun obsLine(ts: Long, zone: String?, visible: Boolean, dets: List<String>): String {
        val zones = if (zone == null) "{}" else
            """{"$zone":{"card_visible":$visible,"still":true,"dets":[${dets.joinToString(",")}]}}"""
        return """{"ts_ms":$ts,"table_visible":true,"zones":$zones}"""
    }

    private fun writeDataset(root: Path, extraPlay: Boolean): Path {
        val truth = root.resolve("truth")
        val obs = root.resolve("obs")
        val out = root.resolve("out")
        Files.createDirectories(truth)
        Files.createDirectories(obs)
        Files.writeString(
            root.resolve("games.json"),
            """
            {"schemaVersion":1,"games":[{"gameId":"$gameId","video":"source/x.mp4",
            "split":"dev","startMs":0,"endMs":2000,"prePlayEndMs":0,"firstPlayMs":0,
            "complete":true,"selfHand":"TWO:2;KING:1","selfHandCount":3,
            "review":"AUTO","notes":""}]}
            """.trimIndent(),
            StandardCharsets.UTF_8
        )
        Files.writeString(
            truth.resolve("$gameId.csv"),
            """
            $header
            $gameId,1,TOP,0,0,200,2,TWO:2,NORMAL,CONFIRMED,
            $gameId,2,RIGHT_TOP,300,300,1000,1,KING:1,NORMAL,CONFIRMED,"人工,复核"
            """.trimIndent(),
            StandardCharsets.UTF_8
        )
        val lines = mutableListOf(
            obsLine(0, "TOP", true, listOf(det("TWO"), det("TWO"))),
            obsLine(100, "TOP", true, listOf(det("TWO"), det("TWO"))),
            obsLine(200, "TOP", false, emptyList()),
            obsLine(300, "RIGHT_TOP", true, listOf(det("KING"))),
            obsLine(400, "RIGHT_TOP", true, listOf(det("KING")))
        )
        if (extraPlay) {
            lines += obsLine(500, "LEFT_TOP", true, listOf(det("ACE")))
            lines += obsLine(600, "LEFT_TOP", true, listOf(det("ACE")))
        }
        Files.writeString(
            obs.resolve("$gameId.jsonl"),
            lines.joinToString("\n", postfix = "\n"),
            StandardCharsets.UTF_8
        )
        return out
    }

    private fun runCli(root: Path, out: Path): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val stdout = PrintStream(buffer, true, StandardCharsets.UTF_8)
        val code = OfflineEvalCli.run(
            arrayOf(
                "--games", root.resolve("games.json").toString(),
                "--truth-dir", root.resolve("truth").toString(),
                "--obs-dir", root.resolve("obs").toString(),
                "--out", out.toString()
            ),
            stdout,
            stdout,
            fastLegacy
        )
        return code to buffer.toString(StandardCharsets.UTF_8)
    }

    @Test
    fun twoHandsReplayThroughLegacyPipelineAndMatchMetrics() {
        val root = Files.createTempDirectory("eval-smoke")
        try {
            val out = writeDataset(root, extraPlay = false)

            val (code, text) = runCli(root, out)

            assertEquals(text, 0, code)
            val json = JSONObject(Files.readString(out.resolve("$gameId.json"), StandardCharsets.UTF_8))
            assertEquals(2, json.getInt("truthHands"))
            assertEquals(2, json.getInt("hits"))
            assertEquals(0, json.getInt("misses"))
            assertEquals(0, json.getInt("extras"))
            assertEquals(0, json.getInt("duplicates"))
            assertEquals(2, json.getInt("quantityExact"))
            assertEquals(1.0, json.getDouble("quantityExactRate"), 0.0)
            assertEquals(2, json.getInt("rankExact"))
            assertEquals(1.0, json.getDouble("rankExactRate"), 0.0)
            assertEquals(2, json.getInt("acceptedPlays"))
            assertEquals(0, json.getInt("pauseCount"))
            assertEquals(0, json.getInt("rejectionCount"))
            val remaining = json.getJSONObject("remaining")
            assertEquals("COMPLETE", json.getString("lockHandCompleteness"))
            assertEquals(0, remaining.getJSONObject("diff").getInt("TWO"))
            assertEquals(0, remaining.getJSONObject("diff").getInt("KING"))
            val summary = Files.readString(out.resolve("summary.md"), StandardCharsets.UTF_8)
            assertTrue(summary.contains(gameId))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun extraAcceptedPlayExitsTwo() {
        val root = Files.createTempDirectory("eval-smoke-extra")
        try {
            val out = writeDataset(root, extraPlay = true)

            val (code, text) = runCli(root, out)

            assertEquals(text, 2, code)
            val json = JSONObject(Files.readString(out.resolve("$gameId.json"), StandardCharsets.UTF_8))
            assertEquals(1, json.getInt("extras"))
            assertEquals(1, json.getInt("extrasOutside"))
            assertEquals(2, json.getInt("hits"))
            val summary = Files.readString(out.resolve("summary.md"), StandardCharsets.UTF_8)
            assertTrue(summary.contains("需要人工复核"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun blindSplitRefusedWithoutFlag() {
        val root = Files.createTempDirectory("eval-smoke-blind")
        try {
            val out = writeDataset(root, extraPlay = false)
            val buffer = ByteArrayOutputStream()
            val stream = PrintStream(buffer, true, StandardCharsets.UTF_8)

            val code = OfflineEvalCli.run(
                arrayOf(
                    "--games", root.resolve("games.json").toString(),
                    "--truth-dir", root.resolve("truth").toString(),
                    "--obs-dir", root.resolve("obs").toString(),
                    "--out", out.toString(),
                    "--split", "blind"
                ),
                stream,
                stream
            )

            assertEquals(1, code)
            assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("盲测集只在参数冻结后由主控跑"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
