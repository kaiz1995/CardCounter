package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.recognition.PlayPipeline
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val code = OfflineEvalCli.run(args)
    if (code != 0) exitProcess(code)
}

/** 便于单测替换管线；默认就是手机现行逻辑的离线等价实现。 */
fun interface PipelineFactory {
    fun create(game: GameMeta): PlayPipeline
}

data class EvalOptions(
    val gamesPath: Path,
    val truthDir: Path,
    val obsDir: Path,
    val outDir: Path,
    val gameId: String?,
    val split: String?,
    val allowBlind: Boolean
)

object OfflineEvalCli {
    const val BLIND_REFUSAL = "盲测集只在参数冻结后由主控跑（需 --allow-blind）"
    private val SPLITS = setOf("dev", "tuning", "blind")

    fun run(
        args: Array<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
        pipelineFactory: PipelineFactory = PipelineFactory { LegacyPipeline() }
    ): Int {
        if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
            printUsage(out)
            return if (args.isEmpty()) 1 else 0
        }
        val options = try {
            parseArgs(args)
        } catch (error: IllegalArgumentException) {
            err.println("错误: ${error.message}")
            printUsage(err)
            return 1
        }
        if (options.split == "blind" && !options.allowBlind) {
            err.println(BLIND_REFUSAL)
            return 1
        }
        return try {
            evaluate(options, out, pipelineFactory)
        } catch (error: DataFormatException) {
            err.println(error.message)
            1
        } catch (error: Exception) {
            err.println("评测失败: ${error.message}")
            1
        }
    }

    private fun evaluate(options: EvalOptions, out: PrintStream, pipelineFactory: PipelineFactory): Int {
        val games = GamesJson.parse(
            readText(options.gamesPath), options.gamesPath.toString()
        )
        val selected = games
            .filter { options.gameId == null || it.gameId == options.gameId }
            .filter { options.split == null || it.split == options.split }
        if (options.gameId != null && selected.isEmpty()) {
            throw DataFormatException("games.json 里找不到 gameId=${options.gameId}（或 split 不匹配）")
        }
        if (selected.isEmpty()) throw DataFormatException("没有可评测的牌局，检查 --split/--game")
        val blind = selected.filter { it.isBlind }
        if (blind.isNotEmpty() && !options.allowBlind) {
            throw DataFormatException(
                "$BLIND_REFUSAL；涉及 ${blind.joinToString(",") { it.gameId }}"
            )
        }
        Files.createDirectories(options.outDir)
        val reports = mutableListOf<GameReport>()
        for (meta in selected) {
            val truthPath = options.truthDir.resolve("${meta.gameId}.csv")
            val obsPath = resolveObservations(options.obsDir, meta.gameId)
            // 自己出的牌完全不管：真值里自己那一区的出牌不参与对齐和剩余牌推算。
            val truth = TruthCsv.parse(readText(truthPath), truthPath.toString(), meta.gameId)
                .filter { it.zone != PlayZone.SELF }
            val frames = ObservationJsonl.parse(readText(obsPath), obsPath.toString())
            val pipeline = pipelineFactory.create(meta)
            val replay = Replay.run(meta, frames, pipeline) { (pipeline as? LegacyPipeline)?.audits.orEmpty() }
            val match = EventMatcher.match(truth, replay.accepted)
            val report = buildGameReport(meta, truth, replay, match)
            reports += report
            writeJson(options.outDir.resolve("${meta.gameId}.json"), report)
            out.println(
                "${meta.gameId}: 命中 ${match.hits.size}/${match.truthHands} " +
                    "漏 ${match.missed.size} 多 ${match.extras.size} 重复 ${match.duplicates.size} " +
                    "张数 ${percent(match.quantityExactRate)} 点数 ${percent(match.rankExactRate)}"
            )
        }
        Files.writeString(
            options.outDir.resolve("summary.md"),
            SummaryWriter.markdown(reports),
            StandardCharsets.UTF_8
        )
        out.println("汇总: ${options.outDir.resolve("summary.md")}")
        val flagged = reports.any { it.flagged }
        if (flagged) out.println("存在多记或重复扣，退出码 2")
        return if (flagged) 2 else 0
    }

    private fun resolveObservations(dir: Path, gameId: String): Path {
        val jsonl = dir.resolve("$gameId.jsonl")
        if (Files.isRegularFile(jsonl)) return jsonl
        val json = dir.resolve("$gameId.json")
        if (Files.isRegularFile(json)) return json
        throw DataFormatException("找不到观察文件: $jsonl")
    }

    private fun readText(path: Path): String {
        if (!Files.isRegularFile(path)) throw DataFormatException("找不到文件: $path")
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun writeJson(path: Path, report: GameReport) {
        Files.writeString(path, report.toJson().toString(2) + "\n", StandardCharsets.UTF_8)
    }

    private fun percent(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.1f%%", value * 100)

    private fun parseArgs(args: Array<String>): EvalOptions {
        var games: Path? = null
        var truth: Path? = null
        var obs: Path? = null
        var output: Path? = null
        var gameId: String? = null
        var split: String? = null
        var allowBlind = false
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            fun value(): String {
                require(index + 1 < args.size) { "$arg 缺少参数值" }
                index++
                return args[index]
            }
            when (arg) {
                "--games" -> games = Paths.get(value())
                "--truth-dir" -> truth = Paths.get(value())
                "--obs-dir" -> obs = Paths.get(value())
                "--out" -> output = Paths.get(value())
                "--game" -> gameId = value()
                "--split" -> {
                    val requested = value()
                    require(requested in SPLITS) { "--split 只接受 dev|tuning|blind，实际 '$requested'" }
                    split = requested
                }
                "--allow-blind" -> allowBlind = true
                else -> throw IllegalArgumentException("未知参数 '$arg'")
            }
            index++
        }
        require(games != null) { "缺少 --games" }
        require(truth != null) { "缺少 --truth-dir" }
        require(obs != null) { "缺少 --obs-dir" }
        require(output != null) { "缺少 --out" }
        return EvalOptions(games!!, truth!!, obs!!, output!!, gameId, split, allowBlind)
    }

    private fun printUsage(out: PrintStream) {
        out.println(
            """
            用法: offline-eval --games <games.json> --truth-dir <dir> --obs-dir <dir> --out <dir>
                                [--game <gameId>] [--split dev|tuning|blind] [--allow-blind]

            用 :core 里与手机相同的状态机和账本回放整局，逐手对照真值：
              --games      datasets/games.json
              --truth-dir  真值 CSV 目录（<gameId>.csv）
              --obs-dir    逐帧观察 JSONL 目录（<gameId>.jsonl）
              --out        输出目录（每局 <gameId>.json + summary.md）
              --game       只评测一局
              --split      只评测指定集合；blind 必须显式加 --allow-blind

            退出码: 0 正常；1 输入/参数错误；2 存在多记或重复扣。
            """.trimIndent()
        )
    }
}
