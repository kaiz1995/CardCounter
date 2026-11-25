package com.example.cardcounter.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫：悬浮窗必须和截屏用同一套整屏坐标（1280×578 设计坐标按物理屏幕缩放）。
 * 所有窗口参数只能经 OverlayService.overlayParams 创建，它负责铺进刘海区、不给系统栏让位、
 * 按物理左上角摆放；横竖屏切换后可触摸小窗要重算。漏掉任何一项，悬浮窗就会和截屏错位，
 * “手牌未确认”标签会落进手牌识别区，被模型认成一张 5。
 */
class OverlayWindowParamsGuardTest {
    private val mainSource: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** 去掉注释后的源码，避免注释里提到的写法误报，也避免把设置注释掉还能通过。 */
    private val sources: Map<String, String> = mainSource.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.name to stripComments(it.readText()) }

    private val overlayService: String = sources.getValue("OverlayService.kt")

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""(?m)(?<![:"])//.*$"""), "")

    /** 取函数体：从声明开始，到下一个同级（4 格缩进）的函数声明或 companion object 为止。 */
    private fun functionBody(text: String, name: String): String {
        val start = Regex("""\bfun\s+$name\s*\(""").find(text)?.range?.first ?: return ""
        val next = Regex("""\n    (?:(?:private|internal|public|protected|override|inline)\s+)*(?:fun\s|companion\s+object)""")
            .find(text, start + 1)?.range?.first ?: text.length
        return text.substring(start, next)
    }

    /** 从 [open]（指向左括号）开始按括号配对，拆出顶层参数。 */
    private fun arguments(text: String, open: Int): List<String> {
        var depth = 0
        val args = mutableListOf<String>()
        val current = StringBuilder()
        for (i in open until text.length) {
            val c = text[i]
            when {
                c == '(' || c == '{' || c == '[' -> { if (depth > 0) current.append(c); depth++ }
                c == ')' || c == '}' || c == ']' -> {
                    depth--
                    if (depth == 0) { args += current.toString().trim(); return args.filter { it.isNotEmpty() } }
                    current.append(c)
                }
                c == ',' && depth == 1 -> { args += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        return args
    }

    @Test
    fun windowParamsAreOnlyConstructedInsideOverlayParams() {
        val qualified = Regex("""WindowManager\s*\.\s*LayoutParams\s*\(""")
        val bare = Regex("""(?<![\w.])LayoutParams\s*\(""")
        val directImport = Regex("""import\s+android\.view\.WindowManager\.(LayoutParams|\*)""")
        val alias = Regex("""typealias\s+\w+\s*=\s*(android\.view\.)?WindowManager\.LayoutParams\b""")
        val hits = sources.flatMap { (name, text) ->
            val found = qualified.findAll(text).count() +
                (if (directImport.containsMatchIn(text)) bare.findAll(text).count() else 0)
            val aliases = alias.findAll(text).map { "$name: ${it.value}" }.toList()
            List(found) { name } + aliases
        }
        assertEquals(
            "悬浮窗参数只能在 OverlayService.overlayParams 里创建一次；新窗口请调用 fullScreenParams() / " +
                "controlParams(...) / overlayParams(...)，不要直接 new WindowManager.LayoutParams，" +
                "也不要用 import 简写或 typealias 绕开。现在出现在：$hits",
            listOf("OverlayService.kt"),
            hits
        )
        assertTrue(
            "WindowManager.LayoutParams 的构造必须写在 overlayParams 里",
            qualified.containsMatchIn(functionBody(overlayService, "overlayParams"))
        )
    }

    @Test
    fun overlayParamsUsesTheWholePhysicalScreen() {
        val body = functionBody(overlayService, "overlayParams")
        assertTrue("没找到 overlayParams 函数", body.isNotEmpty())
        mapOf(
            "FLAG_LAYOUT_IN_SCREEN" to "窗口坐标要按整块屏幕算",
            "LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS" to "横屏时会被挤离刘海区",
            "LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES" to "Android 10 横屏时会被挤离刘海区",
            "fitInsetsTypes = 0" to "Android 11 起默认给可见的状态栏、导航栏让位，窗口被挤开压窄",
            "Gravity.TOP or Gravity.LEFT" to "截屏原点在物理左上角，START 在从右到左的语言下会变成右边"
        ).forEach { (required, why) ->
            assertTrue("overlayParams 缺少 $required：$why，和截屏坐标错位", required in body)
        }
        assertTrue(
            "overlayParams 不要用 Gravity.START：从右到左的语言下会镜像，和截屏坐标错位",
            "Gravity.START" !in body
        )
    }

    @Test
    fun everyAddedOrUpdatedWindowUsesOverlayParams() {
        val call = Regex("""\b(addView|updateViewLayout)\s*\(""")
        val allowed = Regex("""^(fullScreenParams|controlParams|overlayParams)\s*\(""")
        val bad = sources.filter { (_, text) -> "WindowManager" in text }.flatMap { (name, text) ->
            call.findAll(text).mapNotNull { match ->
                val args = arguments(text, match.range.last)
                // 单参数的 addView 是普通 ViewGroup 加子 View，不是悬浮窗。
                if (args.size < 2) return@mapNotNull null
                if (allowed.containsMatchIn(args[1])) null else "$name: ${match.value}${args.joinToString()})"
            }.toList()
        }
        assertTrue(
            "addView / updateViewLayout 的窗口参数必须直接写 fullScreenParams() / controlParams(...) / " +
                "overlayParams(...)，不要先存进变量或另建参数：$bad",
            bad.isEmpty()
        )
    }

    @Test
    fun touchableWindowsAreRelaidOutWhenTheScreenRotates() {
        val body = functionBody(overlayService, "onConfigurationChanged")
        assertTrue("OverlayService 要重写 onConfigurationChanged：小窗按当时屏幕宽高算死像素，转屏后会错位", body.isNotEmpty())
        val added = Regex("""(\w+)\s*=\s*control\(""").findAll(functionBody(overlayService, "attachIfNeeded"))
            .map { it.groupValues[1] }.toList() + "handViews"
        added.forEach { window ->
            assertTrue(
                "转屏后没重算 $window 的位置：在 onConfigurationChanged 里对它调用 updateViewLayout(..., controlParams(...))",
                Regex("""\b$window\b[\s\S]*?updateViewLayout""").containsMatchIn(body)
            )
        }
    }
}
