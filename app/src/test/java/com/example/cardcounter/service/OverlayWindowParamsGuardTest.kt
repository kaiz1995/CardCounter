package com.example.cardcounter.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫：所有悬浮窗的窗口参数只能经 OverlayService.overlayParams 创建。
 * overlayParams 负责铺进刘海区；漏掉时横屏下系统把悬浮窗挤到刘海右边，
 * “手牌未确认”标签会落进手牌识别区，被模型认成一张 5。
 */
class OverlayWindowParamsGuardTest {
    private val mainSource: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private val kotlinFiles: List<File> = mainSource.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val overlayService: File = kotlinFiles.single { it.name == "OverlayService.kt" }

    private fun overlayParamsBody(): String = overlayService.readText()
        .substringAfter("private fun overlayParams(", missingDelimiterValue = "")
        .substringBefore("private fun ")

    @Test
    fun windowParamsAreOnlyConstructedInsideOverlayParams() {
        val constructor = Regex("""WindowManager\.LayoutParams\s*\(""")
        val hits = kotlinFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (constructor.containsMatchIn(line)) "${file.name}:${index + 1}" else null
            }
        }
        assertEquals(
            "悬浮窗参数只能在 OverlayService.overlayParams 里创建一次；新窗口请调用 overlayParams(...)" +
                "（或 fullScreenParams / controlParams），不要直接 new WindowManager.LayoutParams。现在出现在：$hits",
            1,
            hits.size
        )
        assertTrue(
            "WindowManager.LayoutParams 的构造必须写在 overlayParams 里，现在在：$hits",
            constructor.containsMatchIn(overlayParamsBody())
        )
    }

    @Test
    fun overlayParamsLaysWindowsIntoTheCutout() {
        val body = overlayParamsBody()
        assertTrue("没找到 overlayParams 函数", body.isNotEmpty())
        listOf(
            "FLAG_LAYOUT_IN_SCREEN",
            "LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS",
            "LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES",
            "Gravity.TOP or Gravity.START"
        ).forEach { required ->
            assertTrue("overlayParams 缺少 $required：横屏时悬浮窗会被挤离刘海区，和截屏坐标错位", required in body)
        }
    }

    @Test
    fun everyAddedOrUpdatedWindowUsesOverlayParams() {
        val anyCall = Regex("""windowManager\.(addView|updateViewLayout)\(""")
        val call = Regex("""windowManager\.(addView|updateViewLayout)\(\s*\w+\s*,\s*(\w+)\(""")
        val allowed = setOf("fullScreenParams", "controlParams", "overlayParams")
        val bad = kotlinFiles.flatMap { file ->
            val text = file.readText()
            val total = anyCall.findAll(text).count()
            val matched = call.findAll(text).toList()
            val wrong = matched.filter { it.groupValues[2] !in allowed }.map { "${file.name}: ${it.value}" }
            val unparsed = if (matched.size != total) listOf("${file.name}: 有 ${total - matched.size} 处窗口参数没有直接写成函数调用") else emptyList()
            wrong + unparsed
        }
        assertTrue(
            "addView / updateViewLayout 的窗口参数必须直接写 fullScreenParams() 或 controlParams(...)：$bad",
            bad.isEmpty()
        )
    }
}
