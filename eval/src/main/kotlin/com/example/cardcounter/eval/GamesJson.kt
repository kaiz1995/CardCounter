package com.example.cardcounter.eval

import com.example.cardcounter.domain.RankCounts
import org.json.JSONArray
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

    val isBlind: Boolean get() = split == GamesJson.BLIND
}

object GamesJson {
    const val BLIND = "blind"

    /** 与 DATA-FORMATS 第 1 节的集合划分一致，大小写敏感。 */
    val SPLITS: Set<String> = setOf("dev", "tuning", BLIND, "clip")

    fun parse(text: String, source: String = "games.json"): List<GameMeta> {
        val root = try {
            JsonFields.parseObject(text.removePrefix("﻿"))
        } catch (error: IllegalArgumentException) {
            fail(listOf(error.message.orEmpty()), source)
        }
        val games = root.opt("games") as? JSONArray ?: fail(listOf("缺少 games 数组"), source)
        val errors = mutableListOf<String>()
        val result = mutableListOf<GameMeta>()
        val seen = mutableMapOf<String, Int>()
        for (index in 0 until games.length()) {
            val item = games.opt(index)
            if (item !is JSONObject) {
                errors += "games[$index] 不是对象"
                continue
            }
            val before = errors.size
            val meta = parseGame(item, index, errors)
            if (meta != null) {
                val first = seen.putIfAbsent(meta.gameId, index)
                if (first != null) {
                    errors += "games[$index]: gameId '${meta.gameId}' 与 games[$first] 重复"
                }
            }
            if (meta != null && errors.size == before) result += meta
        }
        if (errors.isNotEmpty()) fail(errors, source)
        return result
    }

    private fun parseGame(item: JSONObject, index: Int, errors: MutableList<String>): GameMeta? {
        val at = "games[$index]"
        fun <T> field(read: () -> T): T? = try {
            read()
        } catch (error: IllegalArgumentException) {
            errors += "$at: ${error.message}"
            null
        }
        val gameId = field { JsonFields.string(item, "gameId") }?.trim()
        if (gameId != null && gameId.isEmpty()) errors += "$at: gameId 为空"
        val where = if (gameId.isNullOrEmpty()) at else "$at($gameId)"
        val split = field { JsonFields.string(item, "split") }
        if (split != null && split !in SPLITS) {
            errors += "$where: split 非法 '$split'，只允许 ${SPLITS.joinToString("|")}（小写、无空格）"
        }
        val video = field { JsonFields.string(item, "video") }
        val startMs = field { JsonFields.long(item, "startMs") }
        val prePlayEndMs = field { JsonFields.long(item, "prePlayEndMs") }
        val firstPlayMs = field { JsonFields.long(item, "firstPlayMs") }
        val endMs = field { JsonFields.long(item, "endMs") }
        for ((name, value) in listOf(
            "startMs" to startMs, "prePlayEndMs" to prePlayEndMs, "firstPlayMs" to firstPlayMs, "endMs" to endMs
        )) {
            if (value != null && value < 0) errors += "$where: $name 不能为负: $value"
        }
        val complete = field { JsonFields.boolean(item, "complete") }
        val selfHandText = field { JsonFields.string(item, "selfHand") }
        val hand = selfHandText?.let { text ->
            val handErrors = mutableListOf<String>()
            val (parsed, _) = TruthCsv.parseCards(text, where, handErrors)
            errors += handErrors
            parsed
        }
        val handCount = field { JsonFields.int(item, "selfHandCount") }
        if (handCount != null && handCount < 0) errors += "$where: selfHandCount 不能为负: $handCount"
        if (handCount != null && hand != null && hand.total() > handCount && handCount > 0) {
            errors += "$where: selfHand 合计 ${hand.total()} 超过 selfHandCount $handCount"
        }
        val review = field { JsonFields.string(item, "review", default = "") }
        val notes = field { JsonFields.string(item, "notes", default = "") }
        if (gameId.isNullOrEmpty() || split == null || video == null || startMs == null || prePlayEndMs == null ||
            firstPlayMs == null || endMs == null || complete == null || hand == null || handCount == null ||
            review == null || notes == null
        ) {
            return null
        }
        return GameMeta(
            gameId = gameId,
            video = video,
            split = split,
            startMs = startMs,
            prePlayEndMs = prePlayEndMs,
            firstPlayMs = firstPlayMs,
            endMs = endMs,
            complete = complete,
            selfHand = hand,
            selfHandCount = handCount,
            review = review,
            notes = notes
        )
    }
}
