package com.example.cardcounter.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 机械守卫：只要修改了 RankDecoder.kt 或 tools/dataset/phone_vision.py 中的视觉规则代码，
 * 就必须同步更新两边的 VISION_RULE_VERSION，从而使旧观察文件在评测时失效并强制全量重跑。
 */
class VisionRuleVersionGuardTest {
    private fun repoRoot(): Path =
        if (Files.exists(Path.of("datasets/games.json"))) Path.of(".") else Path.of("..")

    private fun normalizedSlice(path: Path, marker: String): String {
        val text = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n")
        val idx = text.indexOf(marker)
        require(idx >= 0) { "在 $path 中找不到标记 $marker" }
        return text.substring(idx)
            .trimEnd('\n')
            .split('\n')
            .filterNot { it.contains("VISION_RULE_VERSION") }
            .joinToString("\n")
    }

    @Test
    fun declaredVersionMatchesCodeHashAndPythonConstant() {
        val root = repoRoot()
        val rankPath = root.resolve("core/src/main/kotlin/com/example/cardcounter/vision/RankDecoder.kt")
        val visionPath = root.resolve("tools/dataset/phone_vision.py")

        val payload = normalizedSlice(rankPath, "object RankModelContract") +
            "\n---\n" +
            normalizedSlice(visionPath, "CANVAS_W =")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 12)
        val expected = "v3-$digest"

        assertEquals(
            "RankDecoder.kt 或 phone_vision.py 的视觉规则已改，请更新 RankModelContract.VISION_RULE_VERSION 为 $expected 并重跑全部 dev+tuning 观察",
            expected,
            RankModelContract.VISION_RULE_VERSION
        )

        val pyText = Files.readString(visionPath, StandardCharsets.UTF_8)
        val match = Regex("""VISION_RULE_VERSION\s*=\s*"([^"]+)"""").find(pyText)
            ?: error("tools/dataset/phone_vision.py 缺少 VISION_RULE_VERSION")
        assertEquals(
            "tools/dataset/phone_vision.py 的 VISION_RULE_VERSION 与 Kotlin 不一致",
            RankModelContract.VISION_RULE_VERSION,
            match.groupValues[1]
        )
    }
}
