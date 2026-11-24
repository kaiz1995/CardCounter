package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.storage.LedgerCommand
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class RemainingReport(
    val evalRemaining: Map<Rank, Int>,
    val truthRemaining: Map<Rank, Int>,
    val diff: Map<Rank, Int>,
    val unavailableReason: String?
)

data class DecisionRecord(
    val timestampMs: Long,
    val zone: PlayZone?,
    val kind: String,
    val cards: RankCounts,
    val cardCount: Int,
    val seat: String?,
    val result: String,
    val reason: String?
)

data class GameReport(
    val gameId: String,
    val split: String,
    val video: String,
    val match: MatchResult,
    val replay: ReplayResult,
    val remaining: RemainingReport,
    val decisions: List<DecisionRecord>
) {
    val acceptedPlays: Int get() = replay.accepted.size
    val pauses: Int get() = replay.pauseCount
    val rejections: Int get() = replay.rejectionCount
    val flagged: Boolean get() = match.extras.isNotEmpty() || match.duplicates.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("gameId", gameId)
        put("split", split)
        put("video", video)
        put("truthHands", match.truthHands)
        put("hits", match.hits.size)
        put("misses", match.missed.size)
        put("extras", match.extras.size)
        put("extrasOutside", match.extrasOutside)
        put("duplicates", match.duplicates.size)
        put("hitRate", round(match.hitRate))
        put("quantityExact", match.quantityExact)
        put("quantityExactRate", round(match.quantityExactRate))
        put("rankExact", match.rankExact)
        put("rankExactRate", round(match.rankExactRate))
        put("rankKnownTruth", match.rankKnownTruth)
        put("acceptedPlays", acceptedPlays)
        put("pauseCount", pauses)
        put("rejectionCount", rejections)
        put("retryCount", replay.retryCount)
        put("suppressedDuplicateCount", replay.suppressedDuplicateCount)
        put("lockHandCompleteness", replay.lockHandCompleteness.name)
        put(
            "matchWindows",
            JSONObject().put("beforeSlackMs", match.windows.beforeSlackMs)
                .put("afterSlackMs", match.windows.afterSlackMs)
        )
        put("confusionRankOrder", JSONArray(Rank.entries.map { it.name }))
        put("confusion", confusionJson(match.confusion))
        put("perZone", JSONObject().apply {
            match.perZone.forEach { (zone, result) ->
                put(
                    zone.name,
                    JSONObject().apply {
                        put("truthHands", result.truthHands)
                        put("hits", result.hits.size)
                        put("misses", result.missed.size)
                        put("extras", result.extras.size)
                        put("duplicates", result.duplicates.size)
                        put("quantityExact", result.quantityExact)
                        put("rankExact", result.rankExact)
                        put("rankKnownTruth", result.rankKnownTruth)
                    }
                )
            }
        })
        put("remaining", remaining.toJson())
        put("missedPlays", JSONArray(match.missed.map { truthJson(it) }))
        put("extraPlays", JSONArray(match.extras.map { recordJson(it) }))
        put(
            "duplicatePlays",
            JSONArray(match.duplicates.map { duplicate ->
                JSONObject()
                    .put("record", recordJson(duplicate.record))
                    .put("truth", truthJson(duplicate.truth))
            })
        )
        put("decisions", JSONArray(decisions.map { decision ->
            JSONObject().apply {
                put("ts_ms", decision.timestampMs)
                put("zone", decision.zone?.name ?: JSONObject.NULL)
                put("kind", decision.kind)
                put("seat", decision.seat ?: JSONObject.NULL)
                put("cardCount", decision.cardCount)
                put("cards", cardVector(decision.cards))
                put("result", decision.result)
                put("reason", decision.reason ?: JSONObject.NULL)
            }
        }))
    }
}

fun RemainingReport.toJson(): JSONObject = JSONObject().apply {
    if (unavailableReason != null) {
        put("unavailableReason", unavailableReason)
        return@apply
    }
    put("evalRemaining", rankMapJson(evalRemaining))
    put("truthRemaining", rankMapJson(truthRemaining))
    put("diff", rankMapJson(diff))
}

/**
 * 汇总一局。真值推算是同一份 games.json 手牌减去真值出牌；
 * 因此逐点数差只反映“识别/入账漂移”，不校验手牌本身对不对。
 */
fun buildGameReport(
    meta: GameMeta,
    truth: List<TruthPlay>,
    replay: ReplayResult,
    match: MatchResult
): GameReport = GameReport(
    gameId = meta.gameId,
    split = meta.split,
    video = meta.video,
    match = match,
    replay = replay,
    remaining = remainingOf(meta, truth, replay.finalState, replay.lockHandCompleteness),
    decisions = replay.decisions.map(::decisionRecord)
)

const val TRUTH_RANKS_INCOMPLETE = "truth_ranks_incomplete"
const val SELF_HAND_UNKNOWN = "self_hand_unknown"

private fun remainingOf(
    meta: GameMeta,
    truth: List<TruthPlay>,
    finalState: GameState,
    completeness: com.example.cardcounter.domain.RankCompleteness
): RemainingReport {
    val unavailable = when {
        completeness == com.example.cardcounter.domain.RankCompleteness.UNKNOWN -> SELF_HAND_UNKNOWN
        completeness != com.example.cardcounter.domain.RankCompleteness.COMPLETE -> "self_hand_partial"
        !meta.selfHandComplete -> "self_hand_partial"
        // 真值里有看不清点数的外面出牌（cards 留空）：真值剩余本身就不知道，不能拿 0 张去比。
        truth.any { it.zone != PlayZone.SELF && !it.hasRanks } -> TRUTH_RANKS_INCOMPLETE
        !finalState.rankLedgerComplete -> "rank_ledger_incomplete"
        finalState.outsideRemaining == null -> "rank_ledger_incomplete"
        else -> null
    }
    val truthOutside = truth
        .filter { it.zone != PlayZone.SELF }
        .fold(RankCounts.zero()) { sum, play -> sum.plus(play.cards) }
    val truthRemaining = Rank.entries.associateWith { rank ->
        Rank.initialInventory[rank] - meta.selfHand[rank] - truthOutside[rank]
    }
    val evalRemaining = finalState.outsideRemaining
    if (unavailable != null || evalRemaining == null) {
        // 手牌点数不全或真值外面区点数不全时，真值剩余本身算不出来，不给残缺值。
        val truthUnknown = unavailable == "self_hand_partial" || unavailable == SELF_HAND_UNKNOWN ||
            unavailable == TRUTH_RANKS_INCOMPLETE
        val knownTruth = if (truthUnknown) emptyMap() else truthRemaining
        return RemainingReport(emptyMap(), knownTruth, emptyMap(), unavailable ?: "rank_ledger_incomplete")
    }
    val evalMap = Rank.entries.associateWith { evalRemaining[it] }
    return RemainingReport(
        evalRemaining = evalMap,
        truthRemaining = truthRemaining,
        diff = Rank.entries.associateWith { evalMap.getValue(it) - truthRemaining.getValue(it) },
        unavailableReason = null
    )
}

private fun decisionRecord(decision: RecordedDecision): DecisionRecord {
    val command = decision.command
    val kind = when (command) {
        is LedgerCommand.AcceptOutsidePlay -> "AcceptOutsidePlay"
        is LedgerCommand.AcceptSelfPlay -> "AcceptSelfPlay"
        is LedgerCommand.LockHand -> "LockHand"
        is LedgerCommand.NewSession -> "NewSession"
        is LedgerCommand.Pause -> "Pause"
        LedgerCommand.Resume -> "Resume"
    }
    val cards = when (command) {
        is LedgerCommand.AcceptOutsidePlay -> command.cards
        is LedgerCommand.AcceptSelfPlay -> command.cards
        else -> RankCounts.zero()
    }
    val cardCount = when (command) {
        is LedgerCommand.AcceptOutsidePlay -> command.cardCount
        is LedgerCommand.AcceptSelfPlay -> command.cardCount
        else -> 0
    }
    val seat = (command as? LedgerCommand.AcceptOutsidePlay)?.seat?.name
    val result = when (decision.result) {
        is DispatchResult.Accepted -> "Accepted"
        is DispatchResult.AcceptedQuantityOnly -> "AcceptedQuantityOnly"
        is DispatchResult.DuplicateIgnored -> "DuplicateIgnored"
        is DispatchResult.Rejected -> "Rejected"
        is DispatchResult.Paused -> "Paused"
    }
    val reason = when (val value = decision.result) {
        is DispatchResult.Rejected -> value.reason
        is DispatchResult.Paused -> value.reason.displayName
        else -> null
    }
    return DecisionRecord(decision.timestampMs, decision.zone, kind, cards, cardCount, seat, result, reason)
}

fun cardVector(cards: RankCounts): String =
    Rank.entries.filter { cards[it] > 0 }.joinToString(";") { "${it.name}:${cards[it]}" }

private fun truthJson(truth: TruthPlay): JSONObject = JSONObject().apply {
    put("zone", truth.zone.name)
    put("eventSeq", truth.eventSeq)
    put("appear_ms", truth.appearMs)
    put("stable_ms", truth.stableMs)
    put("clear_ms", truth.clearMs)
    put("quantity", truth.quantity)
    put("cards", cardVector(truth.cards))
    put("tag", truth.tag)
    put("review", truth.review)
}

private fun recordJson(record: AcceptedPlay): JSONObject = JSONObject().apply {
    put("ts_ms", record.timestampMs)
    put("zone", record.zone.name)
    put("cardCount", record.cardCount)
    put("cards", cardVector(record.cards))
}

private fun rankMapJson(values: Map<Rank, Int>): JSONObject = JSONObject().apply {
    Rank.entries.forEach { rank -> put(rank.name, values[rank] ?: 0) }
}

/** 稠密 15×15，行 = 真值点数，列 = 识别点数，顺序见 confusionRankOrder。 */
private fun confusionJson(matrix: Array<IntArray>): JSONArray = JSONArray(
    matrix.map { row -> JSONArray(row.toList()) }
)

fun round(value: Double): Double = String.format(Locale.ROOT, "%.4f", value).toDouble()

object SummaryWriter {
    fun markdown(reports: List<GameReport>): String = buildString {
        appendLine("# 离线评测汇总")
        appendLine()
        appendLine("时间窗：真值 stable_ms - ${reports.firstOrNull()?.match?.windows?.beforeSlackMs ?: 500}ms")
        appendLine("到 clear_ms + ${reports.firstOrNull()?.match?.windows?.afterSlackMs ?: 500}ms。")
        appendLine()
        appendLine("| game | split | 真值手数 | 命中 | 漏记 | 多记(外面) | 重复扣 | 张数正确率 | 点数正确率 | 暂停 | 拒绝 |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (report in reports) {
            appendLine(
                "| ${report.gameId} | ${report.split} | ${report.match.truthHands} | " +
                    "${report.match.hits.size} | ${report.match.missed.size} | " +
                    "${report.match.extrasOutside}/${report.match.extras.size} | " +
                    "${report.match.duplicates.size} | " +
                    "${percent(report.match.quantityExactRate)} | " +
                    "${percent(report.match.rankExactRate)} | " +
                    "${report.pauses} | ${report.rejections} |"
            )
        }
        appendLine()
        appendLine(
            "口径：张数正确率 = 张数完全正确的命中手 / 真值手数；" +
                "点数正确率 = 点数完全正确的命中手 / 点数已知的真值手数（cards 为空的手不计入分母）。"
        )
        val unknownHand = reports.filter {
            it.replay.lockHandCompleteness == com.example.cardcounter.domain.RankCompleteness.UNKNOWN
        }
        if (unknownHand.isNotEmpty()) {
            appendLine()
            appendLine(
                "手牌未知（selfHandCount=null）的 ${unknownHand.size} 局按标称 ${Replay.NOMINAL_HAND_COUNT} 张锁定：" +
                    "这些局的守恒暂停可能是标称张数造成的，剩余牌不可算。"
            )
        }
        val flagged = reports.filter { it.flagged }
        if (flagged.isNotEmpty()) {
            appendLine()
            appendLine("## 需要人工复核")
            for (report in flagged) {
                appendLine(
                    "- `${report.gameId}`：多记 ${report.match.extras.size}，" +
                        "重复扣 ${report.match.duplicates.size}（退出码 2）"
                )
            }
        }
        val missingRemaining = reports.filter { it.remaining.unavailableReason != null }
        if (missingRemaining.isNotEmpty()) {
            appendLine()
            appendLine("## 剩余牌不可算")
            for (report in missingRemaining) {
                appendLine("- `${report.gameId}`：${report.remaining.unavailableReason}")
            }
        }
        val withDiff = reports.filter { it.remaining.unavailableReason == null }
        if (withDiff.isNotEmpty()) {
            appendLine()
            appendLine("## 终局外面剩余牌差（识别 - 真值）")
            appendLine()
            appendLine("| game | 逐点数差 |")
            appendLine("|---|---|")
            for (report in withDiff) {
                val diff = report.remaining.diff.filterValues { it != 0 }.entries
                    .joinToString(";") { "${it.key.name}:${it.value}" }
                    .ifEmpty { "0" }
                appendLine("| ${report.gameId} | $diff |")
            }
        }
        val offDiagonal = mutableMapOf<String, Int>()
        for (report in reports) {
            val matrix = report.match.confusion
            for (truthIndex in matrix.indices) {
                for (recordIndex in matrix[truthIndex].indices) {
                    val count = matrix[truthIndex][recordIndex]
                    if (truthIndex == recordIndex || count == 0) continue
                    val key = "${Rank.entries[truthIndex].name}->${Rank.entries[recordIndex].name}"
                    offDiagonal[key] = (offDiagonal[key] ?: 0) + count
                }
            }
        }
        if (offDiagonal.isNotEmpty()) {
            appendLine()
            appendLine("## 点数混淆（真值->识别，非对角）")
            appendLine()
            appendLine("| 混淆 | 张数 |")
            appendLine("|---|---|")
            offDiagonal.entries.sortedByDescending { it.value }.take(10).forEach { (pair, count) ->
                appendLine("| $pair | $count |")
            }
        }
        appendLine()
        appendLine("## 分区")
        for (report in reports) {
            appendLine()
            appendLine("### ${report.gameId}")
            appendLine()
            appendLine("| zone | 真值 | 命中 | 漏记 | 多记 | 重复 |")
            appendLine("|---|---|---|---|---|---|")
            report.match.perZone.filterValues { it.truthHands > 0 || it.extras.isNotEmpty() }.forEach { (zone, result) ->
                appendLine(
                    "| ${zone.name} | ${result.truthHands} | ${result.hits.size} | " +
                        "${result.missed.size} | ${result.extras.size} | ${result.duplicates.size} |"
                )
            }
        }
    }

    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100)
}
