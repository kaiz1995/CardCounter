package com.example.cardcounter.eval

import com.example.cardcounter.domain.RankCounts
import org.json.JSONException
import org.json.JSONObject

/** datasets/games.json 中评测需要的字段。 */
data class GameMeta(
    val gameId: String,
    val video: String,
    val split: String,
    val startMs: Long,
    val prePlayEndMs: Long,
    val firstPlayMs: Long,
    val endMs: Long,
    val complete: Boolean,
    val selfHand: RankCounts,
    val selfHandCount: Int,
    val review: String,
    val notes: String
) {
    /** 手牌点数是否完整：只有完整时才允许声称外面剩余牌精确。 */
    val selfHandComplete: Boolean
        get() = selfHandCount > 0 && selfHand.total() == selfHandCount
}

object GamesJson {
    fun parse(text: String, source: String = "games.json"): List<GameMeta> {
        val root = try {
            JSONObject(text.removePrefix("\uFEFF"))
        } catch (error: JSONException) {
            fail(listOf("不是合法 JSON: ${error.message}"), source)
        }
        val games = root.optJSONArray("games") ?: fail(listOf("缺少 games 数组"), source)
        val errors = mutableListOf<String>()
        val result = mutableListOf<GameMeta>()
        for (index in 0 until games.length()) {
            val item = games.optJSONObject(index)
            if (item == null) {
                errors += "games[$index] 不是对象"
                continue
            }
            val gameId = item.optString("gameId", "").trim()
            if (gameId.isEmpty()) {
                errors += "games[$index].gameId 为空"
                continue
            }
            val selfHandText = item.optString("selfHand", "")
            val handErrors = mutableListOf<String>()
            val (hand, _) = TruthCsv.parseCards(selfHandText, "games[$index]", handErrors)
            handErrors.forEach { errors += "games[$index]($gameId): $it" }
            val handCount = item.optInt("selfHandCount", 0)
            result += GameMeta(
                gameId = gameId,
                video = item.optString("video", ""),
                split = item.optString("split", ""),
                startMs = item.optLong("startMs", 0L),
                prePlayEndMs = item.optLong("prePlayEndMs", 0L),
                firstPlayMs = item.optLong("firstPlayMs", 0L),
                endMs = item.optLong("endMs", 0L),
                complete = item.optBoolean("complete", false),
                selfHand = hand ?: RankCounts.zero(),
                selfHandCount = handCount,
                review = item.optString("review", ""),
                notes = item.optString("notes", "")
            )
        }
        if (errors.isNotEmpty()) fail(errors, source)
        return result
    }
}
