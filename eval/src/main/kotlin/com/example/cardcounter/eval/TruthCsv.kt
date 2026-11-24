package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts

/** 真值里的一手牌，字段对应 docs/rebuild/DATA-FORMATS.md 第 3 节。 */
data class TruthPlay(
    val gameId: String,
    val eventSeq: Int,
    val zone: PlayZone,
    val appearMs: Long,
    val stableMs: Long,
    val clearMs: Long,
    val quantity: Int,
    /** cards 留空时为零向量。 */
    val cards: RankCounts,
    /** cards 列是否非空（点数是否已知）。 */
    val hasRanks: Boolean,
    val tag: String,
    val review: String,
    val notes: String,
    val lineNumber: Int
)

object TruthCsv {
    const val HEADER =
        "game_id,event_seq,zone,appear_ms,stable_ms,clear_ms,quantity,cards,tag,review,notes"

    val TAGS: Set<String> = setOf("NORMAL", "OCCLUDED", "REPLACE_DIRECT", "ANIMATION")
    val REVIEWS: Set<String> = setOf("AUTO", "CONFIRMED", "FIXED")
    private const val FIELD_COUNT = 11

    /**
     * 解析整局真值 CSV。空行跳过；表头必须与 [HEADER] 完全一致。
     * [expectedGameId] 非空时校验每行 game_id，防止张冠李戴。
     */
    fun parse(text: String, source: String = "truth.csv", expectedGameId: String? = null): List<TruthPlay> {
        val errors = mutableListOf<String>()
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var headerLine = 0
        val plays = mutableListOf<TruthPlay>()
        val seenSeq = mutableMapOf<Int, Int>()
        for ((index, raw) in lines.withIndex()) {
            val line = index + 1
            if (raw.isBlank()) continue
            if (headerLine == 0) {
                headerLine = line
                if (raw.trim() != HEADER) {
                    fail(listOf("第 $line 行: 表头不符，期望 '$HEADER'，实际 '${raw.trim()}'"), source)
                }
                continue
            }
            val errorCountBefore = errors.size
            val fields = try {
                splitCsv(raw)
            } catch (error: IllegalArgumentException) {
                errors += "第 $line 行: ${error.message}"
                continue
            }
            if (fields.size != FIELD_COUNT) {
                errors += "第 $line 行: 字段数 ${fields.size}，期望 $FIELD_COUNT"
                continue
            }
            val gameId = fields[0].trim()
            if (gameId.isEmpty()) errors += "第 $line 行: game_id 为空"
            if (expectedGameId != null && gameId != expectedGameId) {
                errors += "第 $line 行: game_id '$gameId' 与文件名/期望 '$expectedGameId' 不一致"
            }
            val eventSeq = parseInt(fields[1], "event_seq", line, errors)
            if (eventSeq != null) {
                if (eventSeq <= 0) {
                    errors += "第 $line 行: event_seq 必须为正数，实际 $eventSeq"
                } else {
                    val firstLine = seenSeq.putIfAbsent(eventSeq, line)
                    if (firstLine != null) {
                        errors += "第 $line 行: event_seq $eventSeq 与第 $firstLine 行重复"
                    }
                }
            }
            val zoneText = fields[2].trim()
            val zone = PlayZone.entries.firstOrNull { it.name == zoneText }
            if (zone == null) {
                errors += "第 $line 行: zone 非法 '$zoneText'，允许 ${PlayZone.entries.joinToString("|") { it.name }}"
            }
            val appearMs = parseLong(fields[3], "appear_ms", line, errors)
            val stableMs = parseLong(fields[4], "stable_ms", line, errors)
            val clearMs = parseLong(fields[5], "clear_ms", line, errors)
            val quantity = parseInt(fields[6], "quantity", line, errors)
            if (quantity != null && quantity <= 0) {
                errors += "第 $line 行: quantity 必须为正数，实际 $quantity"
            }
            if (appearMs != null && appearMs < 0) {
                errors += "第 $line 行: appear_ms 不能为负，实际 $appearMs"
            }
            if (appearMs != null && stableMs != null && stableMs < appearMs) {
                errors += "第 $line 行: stable_ms($stableMs) 早于 appear_ms($appearMs)"
            }
            if (stableMs != null && clearMs != null && clearMs < stableMs) {
                errors += "第 $line 行: clear_ms($clearMs) 早于 stable_ms($stableMs)"
            }
            val (cards, hasRanks) = parseCards(fields[7], "第 $line 行", errors)
            if (cards != null && hasRanks && quantity != null && cards.total() != quantity) {
                errors += "第 $line 行: cards 合计 ${cards.total()} 与 quantity $quantity 不一致"
            }
            val tag = fields[8].trim()
            if (tag !in TAGS) errors += "第 $line 行: tag 非法 '$tag'，允许 ${TAGS.joinToString("|")}"
            val review = fields[9].trim()
            if (review !in REVIEWS) {
                errors += "第 $line 行: review 非法 '$review'，允许 ${REVIEWS.joinToString("|")}"
            }
            if (errors.size == errorCountBefore) {
                plays += TruthPlay(
                    gameId = gameId,
                    eventSeq = eventSeq ?: 0,
                    zone = zone ?: PlayZone.TOP,
                    appearMs = appearMs ?: 0L,
                    stableMs = stableMs ?: 0L,
                    clearMs = clearMs ?: 0L,
                    quantity = quantity ?: 0,
                    cards = cards ?: RankCounts.zero(),
                    hasRanks = hasRanks,
                    tag = tag,
                    review = review,
                    notes = fields[10],
                    lineNumber = line
                )
            }
        }
        if (errors.isNotEmpty()) fail(errors, source)
        if (headerLine == 0) fail(listOf("缺少表头 '$HEADER'"), source)
        return plays
    }

    /** `BIG_JOKER:1;KING:6`；空串表示点数未知。[where] 用于错误信息里的位置前缀。 */
    fun parseCards(field: String, where: String, errors: MutableList<String>): Pair<RankCounts?, Boolean> {
        val text = field.trim()
        if (text.isEmpty()) return RankCounts.zero() to false
        val counts = mutableMapOf<Rank, Int>()
        var lastIndex = -1
        for (part in text.split(';')) {
            val tokens = part.trim().split(':')
            if (tokens.size != 2) {
                errors += "$where: cards 片段非法 '$part'，期望 RANK:张数"
                return null to false
            }
            val rank = Rank.entries.firstOrNull { it.name == tokens[0].trim() }
            if (rank == null) {
                errors += "$where: cards 点数非法 '${tokens[0].trim()}'"
                return null to false
            }
            if (rank.ordinal <= lastIndex) {
                errors += "$where: cards 必须按 Rank 枚举顺序且不重复，'${rank.name}' 位置错误"
                return null to false
            }
            lastIndex = rank.ordinal
            val count = tokens[1].trim().toIntOrNull()
            if (count == null || count <= 0) {
                errors += "$where: cards 张数非法 '${tokens[1].trim()}'"
                return null to false
            }
            counts[rank] = count
        }
        return RankCounts.of(counts) to true
    }

    /**
     * 最小 RFC 4180 切分：双引号只能包裹整个字段，引号内 "" 表示一个引号，逗号只在引号外分隔。
     * 未闭合的引号、未包裹字段里的孤立引号、闭合引号后紧跟非逗号字符都抛 IllegalArgumentException，
     * 不静默删掉引号。
     */
    internal fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var fieldNumber = 1
        while (true) {
            if (index < line.length && line[index] == '"') {
                index++
                var closed = false
                while (index < line.length) {
                    val ch = line[index]
                    if (ch == '"') {
                        if (index + 1 < line.length && line[index + 1] == '"') {
                            current.append('"')
                            index += 2
                            continue
                        }
                        closed = true
                        index++
                        break
                    }
                    current.append(ch)
                    index++
                }
                if (!closed) throw IllegalArgumentException("第 $fieldNumber 列双引号未闭合")
                if (index < line.length && line[index] != ',') {
                    throw IllegalArgumentException("第 $fieldNumber 列闭合引号后有多余字符 '${line[index]}'")
                }
            } else {
                while (index < line.length && line[index] != ',') {
                    if (line[index] == '"') {
                        throw IllegalArgumentException("第 $fieldNumber 列含未包裹的双引号")
                    }
                    current.append(line[index])
                    index++
                }
            }
            fields += current.toString()
            current.setLength(0)
            if (index >= line.length) break
            index++ // 跳过逗号
            fieldNumber++
        }
        return fields
    }
}
