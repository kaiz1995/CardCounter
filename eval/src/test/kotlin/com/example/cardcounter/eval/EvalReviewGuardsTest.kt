package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.recognition.Detection
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.PipelineDecision
import com.example.cardcounter.recognition.PlayPipeline
import com.example.cardcounter.recognition.ZoneObservation
import com.example.cardcounter.storage.LedgerCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** PR #3 审查指出的问题，每条一个守卫：撤掉对应修正，这里就会失败。 */
class EvalReviewGuardsTest {
    private val gameId = "g000001-1"
    private val king = RankCounts.of(mapOf(Rank.KING to 1))

    private fun game(
        split: String = "dev",
        prePlayEndMs: Long = 0,
        endMs: Long = 0,
        selfHand: RankCounts = RankCounts.of(mapOf(Rank.TWO to 2, Rank.KING to 1)),
        selfHandCount: Int = 3
    ) = GameMeta(
        gameId = gameId, video = "source/x.mp4", split = split, startMs = 0,
        prePlayEndMs = prePlayEndMs, firstPlayMs = 0, endMs = endMs, complete = true,
        selfHand = selfHand, selfHandCount = selfHandCount, review = "AUTO", notes = ""
    )

    private fun truth(seq: Int, stableMs: Long, clearMs: Long, cards: RankCounts? = king, zone: PlayZone = PlayZone.TOP) =
        TruthPlay(
            gameId = gameId, eventSeq = seq, zone = zone, appearMs = stableMs - 100,
            stableMs = stableMs, clearMs = clearMs, quantity = cards?.total() ?: 1,
            cards = cards ?: RankCounts.zero(), hasRanks = cards != null,
            tag = "NORMAL", review = "CONFIRMED", notes = "", lineNumber = seq + 1
        )

    private fun accepted(timestampMs: Long) = AcceptedPlay(timestampMs, PlayZone.TOP, king, 1)

    private fun frame(ts: Long, top: ZoneObservation?) =
        FrameObservation(ts, true, if (top == null) emptyMap() else mapOf(PlayZone.TOP to top))

    private fun top(visible: Boolean) = ZoneObservation(
        PlayZone.TOP, visible, true,
        if (visible) listOf(Detection(Rank.KING, 0.9f, 0.5f, 0.42f, 0.12f, 0.22f)) else emptyList()
    )

    private fun formatError(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("应报格式错误，实际 $error", error is DataFormatException)
        return error!!.message.orEmpty()
    }

    // ---- 对齐：相邻两手时间窗重叠 ----

    @Test
    fun adjacentHandsWithOverlappingWindowsBothHit() {
        val result = EventMatcher.match(
            listOf(truth(1, 1_000, 2_000), truth(2, 2_200, 3_200)),
            listOf(accepted(1_950), accepted(2_900))
        )

        assertEquals(2, result.hits.size)
        assertEquals(0, result.missed.size)
        assertEquals(0, result.duplicates.size)
        assertEquals(listOf(1, 2), result.hits.map { it.truth.eventSeq }.sorted())
    }

    @Test
    fun secondRecordInsideFirstHandIsDuplicateNotNextHit() {
        val result = EventMatcher.match(
            listOf(truth(1, 1_000, 2_000), truth(2, 2_100, 3_100)),
            listOf(accepted(1_100), accepted(1_800))
        )

        assertEquals(1, result.hits.size)
        assertEquals(1, result.duplicates.size)
        assertEquals(1, result.duplicates.single().truth.eventSeq)
        assertEquals(2, result.missed.single().eventSeq)
    }

    // ---- 剩余牌差：真值有看不清点数的外面出牌 ----

    @Test
    fun remainingUnavailableWhenOutsideTruthRanksUnknown() {
        val meta = game()
        val replay = Replay.run(meta, emptyList(), LegacyPipeline())
        val unknown = listOf(truth(1, 1_000, 2_000, cards = null))
        val report = buildGameReport(meta, unknown, replay, EventMatcher.match(unknown, replay.accepted))

        assertEquals(TRUTH_RANKS_INCOMPLETE, report.remaining.unavailableReason)
        assertTrue(report.remaining.truthRemaining.isEmpty())
        assertTrue(report.remaining.diff.isEmpty())

        val known = listOf(truth(1, 1_000, 2_000, cards = RankCounts.of(mapOf(Rank.ACE to 1))))
        val control = buildGameReport(meta, known, replay, EventMatcher.match(known, replay.accepted))
        assertNull(control.remaining.unavailableReason)
    }

    // ---- 回放入口 ----

    @Test
    fun partialSelfHandIsNeverLockedAsComplete() {
        val partial = game(selfHand = RankCounts.of(mapOf(Rank.TWO to 2)), selfHandCount = 3)
        assertEquals(RankCompleteness.PARTIAL, Replay.run(partial, emptyList(), LegacyPipeline()).lockHandCompleteness)

        val missingCount = game(selfHandCount = 0)
        val error = runCatching { Replay.run(missingCount, emptyList(), LegacyPipeline()) }.exceptionOrNull()
        assertTrue("selfHandCount=0 应拒绝回放，实际 $error", error is IllegalArgumentException)
    }

    @Test
    fun onlyFramesInsideFormalPlayAreReplayed() {
        val meta = game(prePlayEndMs = 1_000, endMs = 2_000)
        val frames = listOf(frame(2_500, null), frame(500, null), frame(1_500, null), frame(1_000, null))

        assertEquals(listOf(1_000L, 1_500L), Replay.framesInPlay(meta, frames).map { it.timestampMs })
        assertEquals(4, Replay.framesInPlay(game(), frames).size)
    }

    @Test
    fun outsideZoneFallsBackToSeatAndRejectsMissingBoth() {
        fun command(seat: OutsideSeat?) =
            LedgerCommand.AcceptOutsidePlay(king, 1, seat, RankCompleteness.COMPLETE, "e")

        assertEquals(PlayZone.LEFT_TOP, Replay.outsideZoneOf(PlayZone.LEFT_TOP, command(null)))
        assertEquals(PlayZone.RIGHT_TOP, Replay.outsideZoneOf(null, command(OutsideSeat.RIGHT_TOP)))
        assertTrue(runCatching { Replay.outsideZoneOf(null, command(null)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { Replay.outsideZoneOf(PlayZone.SELF, command(null)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun missingZoneKeyIsNoObservationNotEmptyTable() {
        val held = listOf(frame(0, top(true)), frame(100, top(true)), frame(200, null), frame(300, null))
        val pending = Replay.run(game(), held, LegacyPipeline(stableSamples = 100, clearSamples = 1))
        assertTrue("缺省的区不能推进清桌计数", pending.accepted.isEmpty())

        val cleared = Replay.run(game(), held + frame(400, top(false)), LegacyPipeline(stableSamples = 100, clearSamples = 1))
        assertEquals(1, cleared.accepted.size)
        assertEquals(100L, cleared.accepted.single().timestampMs)
    }

    @Test
    fun playAfterSameFramePauseIsSkippedLikePhone() {
        val pipeline = object : PlayPipeline {
            override fun onObservation(observation: FrameObservation, ledger: GameState) = listOf(
                PipelineDecision(LedgerCommand.Pause(PauseReason.CONSERVATION_FAILURE), null, observation.timestampMs),
                PipelineDecision(
                    LedgerCommand.AcceptOutsidePlay(
                        RankCounts.of(mapOf(Rank.ACE to 1)), 1, OutsideSeat.TOP, RankCompleteness.COMPLETE, "e1"
                    ),
                    PlayZone.TOP,
                    observation.timestampMs
                )
            )

            override fun reset() {}
        }

        val replay = Replay.run(game(), listOf(frame(0, null)), pipeline)

        assertEquals(1, replay.decisions.size)
        assertEquals(0, replay.rejectionCount)
        assertTrue(replay.accepted.isEmpty())
    }

    // ---- games.json ----

    private fun gameJson(
        id: String = gameId,
        split: String? = "\"dev\"",
        handCount: String = "3",
        startMs: String = "0"
    ): String {
        val splitField = if (split == null) "" else "\"split\":$split,"
        return """{"gameId":"$id","video":"source/x.mp4",$splitField"startMs":$startMs,"endMs":2000,""" +
            """"prePlayEndMs":0,"firstPlayMs":0,"complete":true,"selfHand":"TWO:2;KING:1",""" +
            """"selfHandCount":$handCount,"review":"AUTO","notes":""}"""
    }

    private fun gamesFile(vararg games: String) = """{"schemaVersion":1,"games":[${games.joinToString(",")}]}"""

    @Test
    fun gamesJsonAcceptsValidGame() {
        val meta = GamesJson.parse(gamesFile(gameJson())).single()
        assertEquals("dev", meta.split)
        assertEquals(3, meta.selfHandCount)
        assertTrue(meta.selfHandComplete)
    }

    @Test
    fun gamesJsonRejectsBadFields() {
        val cases = listOf(
            "split 非法" to gamesFile(gameJson(split = "\"BLIND\"")),
            "split 非法" to gamesFile(gameJson(split = "\" blind \"")),
            "split" to gamesFile(gameJson(split = null)),
            "selfHandCount" to gamesFile(gameJson(handCount = "\"33张\"")),
            "selfHandCount" to gamesFile(gameJson(handCount = "null")),
            "startMs" to gamesFile(gameJson(startMs = "1.5")),
            "重复" to gamesFile(gameJson(), gameJson())
        )
        for ((expected, text) in cases) {
            val message = formatError { GamesJson.parse(text) }
            assertTrue("应提到 '$expected'，实际: $message", message.contains(expected))
        }
    }

    // ---- 盲测拦截 ----

    private fun writeDataset(root: Path, split: String) {
        Files.createDirectories(root.resolve("truth"))
        Files.createDirectories(root.resolve("obs"))
        Files.writeString(root.resolve("games.json"), gamesFile(gameJson(split = "\"$split\"")), StandardCharsets.UTF_8)
        Files.writeString(
            root.resolve("truth").resolve("$gameId.csv"),
            TruthCsv.HEADER + "\n$gameId,1,TOP,0,0,200,1,KING:1,NORMAL,CONFIRMED,\n",
            StandardCharsets.UTF_8
        )
        Files.writeString(
            root.resolve("obs").resolve("$gameId.jsonl"),
            "{\"ts_ms\":0,\"table_visible\":true,\"zones\":{}}\n",
            StandardCharsets.UTF_8
        )
    }

    private fun cli(root: Path, vararg extra: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val stream = PrintStream(buffer, true, StandardCharsets.UTF_8)
        val code = OfflineEvalCli.run(
            arrayOf(
                "--games", root.resolve("games.json").toString(),
                "--truth-dir", root.resolve("truth").toString(),
                "--obs-dir", root.resolve("obs").toString(),
                "--out", root.resolve("out").toString(),
                *extra
            ),
            stream,
            stream
        )
        return code to buffer.toString(StandardCharsets.UTF_8)
    }

    private fun withDataset(split: String, block: (Path) -> Unit) {
        val root = Files.createTempDirectory("eval-guard")
        try {
            writeDataset(root, split)
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun blindGameRefusedByGameIdAndByFullRun() = withDataset("blind") { root ->
        val (byId, byIdText) = cli(root, "--game", gameId)
        assertEquals(byIdText, 1, byId)
        assertTrue(byIdText.contains(OfflineEvalCli.BLIND_REFUSAL))

        val (all, allText) = cli(root)
        assertEquals(allText, 1, all)
        assertTrue(allText.contains(OfflineEvalCli.BLIND_REFUSAL))
    }

    @Test
    fun blindGameRunsWithAllowBlind() = withDataset("blind") { root ->
        val (code, text) = cli(root, "--game", gameId, "--allow-blind")
        assertEquals(text, 0, code)
    }

    @Test
    fun uppercaseBlindCannotSlipPastRefusal() = withDataset("BLIND") { root ->
        val (code, text) = cli(root, "--game", gameId)
        assertEquals(text, 1, code)
        assertTrue(text, text.contains("split 非法"))
    }

    // ---- 逐帧观察 JSONL ----

    @Test
    fun observationRejectsSilentlyLenientInput() {
        val det = """{"rank":"KING","conf":0.9,"cx":0.5,"cy":0.42,"w":0.12,"h":0.22}"""
        val cases = listOf(
            "zones" to """{"ts_ms":1,"table_visible":true}""",
            "zones" to """{"ts_ms":1,"table_visible":true,"zones":[]}""",
            "still" to """{"ts_ms":1,"table_visible":true,"zones":{"TOP":{"card_visible":true}}}""",
            "conf" to """{"ts_ms":1,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"dets":[${det.replace("0.9", "NaN")}]}}}""",
            "conf" to """{"ts_ms":1,"table_visible":true,"zones":{"TOP":{"card_visible":true,"still":true,"dets":[${det.replace("0.9", "\"NaN\"")}]}}}""",
            "多余内容" to """{"ts_ms":1,"table_visible":true,"zones":{}}{"ts_ms":2,"table_visible":true,"zones":{}}""",
            "ts_ms" to """{"ts_ms":1.5,"table_visible":true,"zones":{}}""",
            "ts_ms" to """{"ts_ms":-1,"table_visible":true,"zones":{}}""",
            "table_visible" to """{"ts_ms":1,"table_visible":"true","zones":{}}"""
        )
        for ((expected, line) in cases) {
            val message = formatError { ObservationJsonl.parse(line) }
            assertTrue("应提到 '$expected'，实际: $message", message.contains(expected))
        }
    }

    // ---- 真值 CSV ----

    @Test
    fun truthRejectsBadQuotesAndSequence() {
        val ok = "$gameId,1,TOP,0,0,200,1,KING:1,NORMAL,CONFIRMED,"
        val cases = listOf(
            "未闭合" to "$ok\"未闭合",
            "未包裹" to "$gameId,1,TO\"P,0,0,200,1,KING:1,NORMAL,CONFIRMED,",
            "多余字符" to "$ok\"备注\"x",
            "必须为正" to "$gameId,0,TOP,0,0,200,1,KING:1,NORMAL,CONFIRMED,",
            "重复" to "$ok\n$gameId,1,TOP,300,300,500,1,KING:1,NORMAL,CONFIRMED,",
            "不能为负" to "$gameId,1,TOP,-1,0,200,1,KING:1,NORMAL,CONFIRMED,"
        )
        for ((expected, rows) in cases) {
            val message = formatError { TruthCsv.parse(TruthCsv.HEADER + "\n" + rows) }
            assertTrue("应提到 '$expected'，实际: $message", message.contains(expected))
        }
    }
}
