package com.example.cardcounter.capture

/**
 * 数出来的手牌上下排张数对（top to bottom）的多帧投票：
 * 和点数一样，同一个停稳画面（scene）里取出现次数最多的张数对，不用最新的单帧。
 * 次数相同取最近出现的；某一帧没数出来（null）不投票。画面变了（scene 编号变了）就清空从头再来。
 * 非线程安全，由调用方串行使用。
 */
class HandPositionVoter {
    private var scene: Long? = null
    private val votes = HashMap<Pair<Int, Int>, Int>()
    private val lastSeen = HashMap<Pair<Int, Int>, Long>()
    private var tick = 0L

    fun reset() {
        scene = null
        votes.clear()
        lastSeen.clear()
        tick = 0L
    }

    /** 并入这一帧数出来的两排张数，返回当前画面的众数；这个画面还一次都没数出来时为 null。 */
    fun add(scene: Long, rows: Pair<Int, Int>?): Pair<Int, Int>? {
        if (this.scene != scene) {
            reset()
            this.scene = scene
        }
        if (rows != null) {
            votes[rows] = (votes[rows] ?: 0) + 1
            lastSeen[rows] = ++tick
        }
        return votes.entries
            .maxWithOrNull(compareBy<Map.Entry<Pair<Int, Int>, Int>> { it.value }.thenBy { lastSeen.getValue(it.key) })
            ?.key
    }
}
