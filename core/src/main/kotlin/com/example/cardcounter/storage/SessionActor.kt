package com.example.cardcounter.storage

import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.RankCounts
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 账本命令：所有账本写入都必须包装成命令提交给 [SessionActor] 串行执行。
 * ConfirmHand / AcceptOutsidePlay 携带 evidenceId，作为账本事件 id，实现重试幂等去重。
 */
sealed class LedgerCommand {
    abstract val evidenceId: String?

    /** 开新局；evidenceId 兼作 NewSession 事件 id。 */
    data class NewSession(
        override val evidenceId: String = UUID.randomUUID().toString()
    ) : LedgerCommand()

    /** 确认自己的手牌（每局一次）；handCount 是按牌边缘数出来的张数，必须等于 cards.total()。 */
    data class ConfirmHand(
        val cards: RankCounts,
        val handCount: Int,
        override val evidenceId: String = UUID.randomUUID().toString()
    ) : LedgerCommand()

    /** 接受一次外面出牌（点数完整，必须带座位）。 */
    data class AcceptOutsidePlay(
        val cards: RankCounts,
        val seat: OutsideSeat,
        override val evidenceId: String
    ) : LedgerCommand()

    /** 保护性/人工暂停。 */
    data class Pause(
        val reason: PauseReason
    ) : LedgerCommand() {
        override val evidenceId: String? get() = null
    }

    /** 显式恢复：只有该命令能把 PAUSED 拉回 ACTIVE，重启不会自动恢复。 */
    data object Resume : LedgerCommand() {
        override val evidenceId: String? get() = null
    }
}

/**
 * 账本写入网关：生产实现为 SessionRepository；单测可替换为内存实现，
 * 保证 SessionActor 自身可在 JVM 上无 android 依赖地测试。
 */
interface LedgerGateway {
    val state: GameState

    fun dispatchDetailed(event: GameEvent): DispatchResult
}

/**
 * 单写者：内部单线程串行执行所有 [LedgerCommand]，任何时刻只有一个写入者访问账本。
 * - [submit] 异步排队，结果经回调返回；
 * - [post] 同步阻塞，供测试与需要严格顺序的调用方使用。
 */
class SessionActor(private val repository: LedgerGateway) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "session-actor-writer").apply { isDaemon = true }
    }

    fun submit(command: LedgerCommand, callback: ((DispatchResult) -> Unit)? = null) {
        try {
            executor.execute {
                val result = applyCommand(command)
                callback?.invoke(result)
            }
        } catch (_: RejectedExecutionException) {
            callback?.invoke(rejected("会话写入线程已关闭"))
        }
    }

    /** 同步阻塞版：命令执行完毕后才返回。 */
    fun post(command: LedgerCommand): DispatchResult = try {
        executor.submit(Callable { applyCommand(command) }).get()
    } catch (_: RejectedExecutionException) {
        rejected("会话写入线程已关闭")
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        rejected("会话写入线程被中断")
    } catch (_: ExecutionException) {
        rejected("会话写入失败")
    }

    /** 停止接收新命令并等待队列中已有命令执行完毕。 */
    fun shutdown() {
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    /** reducer 抛出的真·程序错误降级为 Rejected，不炸写入线程；被拒事件不落账。 */
    private fun applyCommand(command: LedgerCommand): DispatchResult =
        runCatching { repository.dispatchDetailed(toEvent(command)) }
            .getOrElse { rejected("账本命令执行异常: ${it.message}") }

    private fun rejected(reason: String): DispatchResult =
        DispatchResult.Rejected(repository.state, reason)

    private fun toEvent(command: LedgerCommand): GameEvent = when (command) {
        is LedgerCommand.NewSession -> GameEvent.NewSession(command.evidenceId)
        is LedgerCommand.ConfirmHand -> GameEvent.HandConfirmed(
            command.cards, command.handCount, command.evidenceId
        )
        is LedgerCommand.AcceptOutsidePlay -> GameEvent.OutsideCardsPlayed(
            command.cards, command.seat, command.evidenceId
        )
        is LedgerCommand.Pause -> GameEvent.SessionPaused(command.reason)
        LedgerCommand.Resume -> GameEvent.SessionResumed()
    }
}
