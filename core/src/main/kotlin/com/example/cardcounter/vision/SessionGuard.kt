package com.example.cardcounter.vision

/**
 * 推理会话的关闭保护：推理和关闭互斥，关闭后再推理直接返回 [runIfOpen] 的默认值。
 * 识别线程可能正在 native 推理，这时主线程关闭会话会闪退，所以关闭必须等进行中的这一次推理结束。
 * 检测器的 detect 用 [runIfOpen] 包起来，close 用 [close] 包起来。
 */
class SessionGuard {
    private var closed = false

    @Synchronized
    fun <T> runIfOpen(whenClosed: T, block: () -> T): T = if (closed) whenClosed else block()

    /** 第一次调用时在锁里执行 [release]（会等进行中的推理结束）；重复调用什么都不做。 */
    @Synchronized
    fun close(release: () -> Unit) {
        if (closed) return
        closed = true
        release()
    }

    @get:Synchronized
    val isClosed: Boolean get() = closed
}
