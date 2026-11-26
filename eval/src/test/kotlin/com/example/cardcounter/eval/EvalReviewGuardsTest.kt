package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.recognition.Detection
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.PipelineDecision
import com.example.cardcounter.recognition.PlayPipeline
import com.example.cardcounter.recognition.ZoneObservation
import com.example.cardcounter.storage.LedgerCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        selfHandCount: Int? = 3
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
    fun partialSelfHandIsNeverConfirmed() {
        val partial = game(selfHand = RankCounts.of(mapOf(Rank.TWO to 2)), selfHandCount = 3)
        val partialReplay = Replay.run(partial, emptyList(), LegacyPipeline())
        assertFalse(partialReplay.handConfirmed)
        assertNull(partialReplay.finalState.selfHand)

        val complete = Replay.run(game(), emptyList(), LegacyPipeline())
        assertTrue(complete.handConfirmed)
        assertEquals(RankCounts.of(mapOf(Rank.TWO to 2, Rank.KING to 1)), complete.finalState.selfHand)

        val missingCount = game(selfHandCount = 0)
        val error = runCatching { Replay.run(missingCount, emptyList(), LegacyPipeline()) }.exceptionOrNull()
        assertTrue("selfHandCount=0 应拒绝回放，实际 $error", error is IllegalArgumentException)
    }

    @Test
    fun onlyFramesInsideFormalPlayAreReplayed() {
        val meta = game(prePlayEndMs = 1_000, endMs = 2_000)
        val frames = listOf(frame(2_500, null), frame(500, null), frame(1_500, null), frame(1_000, null), frame(2_000, null))

        assertEquals(listOf(1_000L, 1_500L, 2_000L), Replay.framesInPlay(meta, frames).map { it.timestampMs })
        assertEquals(5, Replay.framesInPlay(game(), frames).size)
    }

    @Test
    fun outsideZoneFallsBackToSeatAndRejectsSelfZone() {
        fun command(seat: OutsideSeat) = LedgerCommand.AcceptOutsidePlay(king, seat, "e")

        assertEquals(PlayZone.LEFT_TOP, Replay.outsideZoneOf(PlayZone.LEFT_TOP, command(OutsideSeat.LEFT_TOP)))
        assertEquals(PlayZone.RIGHT_TOP, Replay.outsideZoneOf(null, command(OutsideSeat.RIGHT_TOP)))
        assertTrue(
            runCatching { Replay.outsideZoneOf(PlayZone.SELF, command(OutsideSeat.TOP)) }.exceptionOrNull()
                is IllegalArgumentException
        )
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
                        RankCounts.of(mapOf(Rank.ACE to 1)), OutsideSeat.TOP, "e1"
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
            "必须为空" to gamesFile(gameJson(handCount = "null")),
            "缺少字段 selfHandCount" to gamesFile(gameJson().replace(""""selfHandCount":3,""", "")),
            "必须为正" to gamesFile(gameJson(handCount = "0")),
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

    // ---- 第二轮复审：空隙入账、同帧暂停、残缺真值剩余 ----

    @Test
    fun gapRecordDoesNotStealNextHandThatHasCoreRecord() {
        // T1 晚 60ms 入账，离 T2 更近（40ms），但 T2 核心区间里已有自己的入账。
        val result = EventMatcher.match(
            listOf(truth(1, 1_000, 2_000), truth(2, 2_100, 3_100)),
            listOf(accepted(2_060), accepted(2_500))
        )

        assertEquals(0, result.duplicates.size)
        assertEquals(0, result.missed.size)
        assertEquals(mapOf(1 to 2_060L, 2 to 2_500L), result.hits.associate { it.truth.eventSeq to it.record.timestampMs })
    }

    @Test
    fun earlyGapRecordHitsUnmatchedNextHand() {
        // T1 清桌后、T2 停稳前提前入账，离已配对的 T1 更近，也应配给还没配对的 T2。
        val result = EventMatcher.match(
            listOf(truth(1, 1_000, 2_000), truth(2, 2_400, 3_400)),
            listOf(accepted(1_500), accepted(2_150))
        )

        assertEquals(2, result.hits.size)
        assertEquals(0, result.duplicates.size)
        assertEquals(0, result.missed.size)
    }

    @Test
    fun gapRecordIsDuplicateWhenBothHandsAlreadyHit() {
        val result = EventMatcher.match(
            listOf(truth(1, 1_000, 2_000), truth(2, 2_100, 3_100)),
            listOf(accepted(1_500), accepted(2_060), accepted(2_500))
        )

        assertEquals(2, result.hits.size)
        assertEquals(1, result.duplicates.size)
        assertEquals(0, result.extras.size)
    }

    private fun kings(zone: PlayZone, count: Int) = ZoneObservation(
        zone, count > 0, true,
        List(count) { Detection(Rank.KING, 0.9f, 0.3f + 0.2f * it, 0.42f, 0.12f, 0.22f) }
    )

    @Test
    fun realPipelineSkipsLaterZoneAfterSameFramePause() {
        // 自己手里 16 张 K（库存用尽），外面再出 K 就超库存 → 守恒暂停。TOP 排在 LEFT_TOP 前面，两区同一帧清桌交出。
        val meta = game(selfHand = RankCounts.of(mapOf(Rank.KING to 16)), selfHandCount = 16)
        fun frameOf(ts: Long, top: Int, left: Int) = FrameObservation(
            ts, true, mapOf(PlayZone.TOP to kings(PlayZone.TOP, top), PlayZone.LEFT_TOP to kings(PlayZone.LEFT_TOP, left))
        )
        val pipeline = LegacyPipeline(stableSamples = 100, clearSamples = 1)
        val replay = Replay.run(
            meta, listOf(frameOf(0, 2, 1), frameOf(100, 2, 1), frameOf(200, 0, 0)), pipeline
        ) { pipeline.audits }

        assertEquals(1, replay.pauseCount)
        assertEquals(0, replay.rejectionCount)
        assertEquals(listOf<PlayZone?>(PlayZone.TOP), replay.decisions.map { it.zone })
        assertTrue(replay.accepted.isEmpty())
        // 手机读到非 ACTIVE 直接返回，不调 accept：后一区审计应是 NOT_ACTIVE，不是 ACCEPTED。
        assertEquals(
            listOf(PlayZone.TOP to LegacyOutcome.ACCEPTED, PlayZone.LEFT_TOP to LegacyOutcome.NOT_ACTIVE),
            replay.audits.map { it.zone to it.outcome }
        )
        assertEquals(1, replay.notActiveCount)
    }

    @Test
    fun partialSelfHandReportsNoTruthRemaining() {
        val partial = game(selfHand = RankCounts.of(mapOf(Rank.TWO to 2)), selfHandCount = 3)
        val replay = Replay.run(partial, emptyList(), LegacyPipeline())
        for (plays in listOf(listOf(truth(1, 1_000, 2_000)), listOf(truth(1, 1_000, 2_000, cards = null)))) {
            val report = buildGameReport(partial, plays, replay, EventMatcher.match(plays, replay.accepted))
            assertEquals(SELF_HAND_UNKNOWN, report.remaining.unavailableReason)
            assertTrue(report.remaining.truthRemaining.isEmpty())
        }
    }

    // ---- 手牌未知（selfHandCount=null） ----

    @Test
    fun gamesJsonAcceptsUnknownSelfHand() {
        val text = gamesFile(
            gameJson().replace(""""selfHand":"TWO:2;KING:1","selfHandCount":3""", """"selfHand":"","selfHandCount":null""")
        )
        val meta = GamesJson.parse(text).single()
        assertNull(meta.selfHandCount)
        assertEquals(0, meta.selfHand.total())
        assertTrue(!meta.selfHandComplete)
    }

    @Test
    fun unknownSelfHandIsNotConfirmedAndReportsNoRemaining() {
        val unknown = game(selfHand = RankCounts.zero(), selfHandCount = null)
        val replay = Replay.run(unknown, emptyList(), LegacyPipeline())
        assertFalse(replay.handConfirmed)
        assertNull(replay.finalState.selfHand)
        // 未确认：横条不扣自己的牌。
        assertEquals(com.example.cardcounter.domain.Rank.initialInventory, replay.finalState.outsideRemaining)

        val plays = listOf(truth(1, 1_000, 2_000))
        val report = buildGameReport(unknown, plays, replay, EventMatcher.match(plays, replay.accepted))
        assertEquals(SELF_HAND_UNKNOWN, report.remaining.unavailableReason)
        assertTrue(report.remaining.truthRemaining.isEmpty())
        assertTrue(SummaryWriter.markdown(listOf(report)).contains("手牌未知"))
    }

    @Test
    fun unknownSelfHandStillCountsOutsidePlays() {
        val unknown = game(selfHand = RankCounts.zero(), selfHandCount = null)
        fun frameOf(ts: Long, top: Int, badge: Boolean = false) = FrameObservation(
            ts,
            true,
            mapOf(
                PlayZone.TOP to ZoneObservation(
                    PlayZone.TOP,
                    top > 0,
                    true,
                    List(top) { Detection(Rank.KING, 0.9f, 0.3f + 0.2f * it, 0.42f, 0.12f, 0.22f) },
                    turnBadge = badge
                )
            )
        )
        val pipeline = LegacyPipeline(stableSamples = 100, clearSamples = 1)
        val replay = Replay.run(
            unknown,
            listOf(
                frameOf(0, 0, badge = true),
                frameOf(50, 0, badge = true),
                frameOf(100, 2),
                frameOf(150, 2),
                frameOf(250, 0)
            ),
            pipeline
        ) {
            pipeline.audits
        }
        assertEquals(0, replay.pauseCount)
        assertEquals(0, replay.rejectionCount)
        assertEquals(1, replay.accepted.size)
        assertEquals(2, replay.finalState.outsidePlayedCount)
    }

    // ---- 第 1 类：已逐帧核实的 dev 真值漏标/错标与 firstPlayMs 边界 ----

    @Test
    fun fixedDevTruthMatchesVerifiedVideoFrames() {
        val root = if (Files.exists(Path.of("datasets/games.json"))) Path.of(".") else Path.of("..")
        val games = GamesJson.parse(Files.readString(root.resolve("datasets/games.json"), StandardCharsets.UTF_8))
            .associateBy { it.gameId }
        fun loadTruth(gameId: String): List<TruthPlay> =
            TruthCsv.parse(Files.readString(root.resolve("datasets/truth/$gameId.csv"), StandardCharsets.UTF_8), gameId)

        val g1Plays = loadTruth("g102658-1")
        val g1First = g1Plays.minBy { it.appearMs }
        assertTrue("g102658-1 首手 TEN:4 不应早于 firstPlayMs", g1First.appearMs >= games.getValue("g102658-1").firstPlayMs)
        assertTrue(
            "g102658-1 应包含 36563..38212 LEFT_BOTTOM TEN:4",
            g1Plays.any { it.zone == PlayZone.LEFT_BOTTOM && it.appearMs == 36563L && it.cards == RankCounts.of(mapOf(Rank.TEN to 4)) }
        )

        val g2Plays = loadTruth("g102658-2")
        assertTrue(
            "g102658-2 应包含 364470..364722 RIGHT_TOP BIG_JOKER:1;TEN:4",
            g2Plays.any {
                it.zone == PlayZone.RIGHT_TOP && it.appearMs == 364470L && it.clearMs == 364722L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.TEN to 4))
            }
        )
        assertTrue(
            "g102658-2 应包含 374925..375170 LEFT_BOTTOM BIG_JOKER:1;EIGHT:3",
            g2Plays.any {
                it.zone == PlayZone.LEFT_BOTTOM && it.appearMs == 374925L && it.clearMs == 375170L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.EIGHT to 3))
            }
        )
        assertTrue(
            "g102658-2 LEFT_BOTTOM 375170 实为 BIG_JOKER:1;QUEEN:4（非 QUEEN:5）",
            g2Plays.any {
                it.zone == PlayZone.LEFT_BOTTOM && it.stableMs == 375170L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.QUEEN to 4))
            }
        )

        val g3Plays = loadTruth("g102658-3")
        assertTrue(
            "g102658-3 应包含 520461..520708 RIGHT_TOP BIG_JOKER:1;TWO:1;TEN:2",
            g3Plays.any {
                it.zone == PlayZone.RIGHT_TOP && it.appearMs == 520461L && it.clearMs == 520708L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.TWO to 1, Rank.TEN to 2))
            }
        )
        assertTrue(
            "g102658-3 RIGHT_TOP 520708 实为 BIG_JOKER:1;KING:6（非 KING:7）",
            g3Plays.any {
                it.zone == PlayZone.RIGHT_TOP && it.stableMs == 520708L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.KING to 6))
            }
        )

        val g5Plays = loadTruth("g102658-5")
        assertTrue(
            "g102658-5 RIGHT_BOTTOM 863308 实为 BIG_JOKER:1;TWO:1;NINE:4",
            g5Plays.any {
                it.zone == PlayZone.RIGHT_BOTTOM && it.appearMs == 863308L && it.clearMs == 863564L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.TWO to 1, Rank.NINE to 4))
            }
        )
        // 863641/863741 截图王牌竖条为红字 JOKER（大王），气泡“挂大王”
        assertTrue(
            "g102658-5 应包含 863564..863809 RIGHT_BOTTOM BIG_JOKER:1;JACK:4",
            g5Plays.any {
                it.zone == PlayZone.RIGHT_BOTTOM && it.appearMs == 863564L && it.clearMs == 863809L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.JACK to 4))
            }
        )
        // 844223 截图王牌竖条为红字 JOKER（大王），与 845121 那手大王同色；828474 那手才是黑字小王
        assertTrue(
            "g102658-5 SELF 843325 实为 BIG_JOKER:1;JACK:2（非小王）",
            g5Plays.any {
                it.zone == PlayZone.SELF && it.appearMs == 843325L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.JACK to 2))
            }
        )
        val g5Big = g5Plays.sumOf { it.cards[Rank.BIG_JOKER] }
        val g5Small = g5Plays.sumOf { it.cards[Rank.SMALL_JOKER] }
        assertEquals("g102658-5 大王/小王各 4 张（4 副牌）", 4 to 4, g5Big to g5Small)

        val g141Plays = loadTruth("g143651-1")
        assertTrue(
            "g143651-1 应包含 177765..178017 TOP BIG_JOKER:1;SEVEN:2",
            g141Plays.any {
                it.zone == PlayZone.TOP && it.appearMs == 177765L && it.clearMs == 178017L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.SEVEN to 2))
            }
        )

        val g145Plays = loadTruth("g143651-5")
        assertTrue(
            "g143651-5 应包含 852475..852724 LEFT_BOTTOM BIG_JOKER:1;QUEEN:2",
            g145Plays.any {
                it.zone == PlayZone.LEFT_BOTTOM && it.appearMs == 852475L && it.clearMs == 852724L &&
                    it.cards == RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.QUEEN to 2))
            }
        )
    }
}
