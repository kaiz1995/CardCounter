package com.example.cardcounter.capture

import java.io.File
import java.io.InputStream
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicRankTemplateTest {
    @Test
    fun recognizesCurrentPublicFiveAtTrueSize() {
        val scene = raster(requireNotNull(javaClass.getResourceAsStream("/g3/public_five.png")))
        val template = template("rank_five_play.png")

        assertEquals(14, template.width)
        assertEquals(14, template.height)
        assertTrue(bestIoU(scene, template) >= 0.55)
    }

    @Test
    fun recognizesCurrentPublicFourAtTrueSize() {
        val scene = raster(requireNotNull(javaClass.getResourceAsStream("/g4/public_four.png")))
        val template = template("rank_four_play.png")

        assertEquals(14, template.width)
        assertEquals(14, template.height)
        assertTrue(bestIoU(scene, template) >= 0.55)
    }

    @Test
    fun recognizesCurrentPublicTenQueenAndEightAtTrueSize() {
        mapOf(
            "ten" to "rank_ten_play.png",
            "queen" to "rank_queen_play.png",
            "eight" to "rank_eight_play.png"
        ).forEach { (rank, templateName) ->
            val scene = raster(requireNotNull(javaClass.getResourceAsStream("/g4/public_$rank.png")))
            val template = template(templateName)

            assertEquals(14, template.width)
            assertEquals(14, template.height)
            assertTrue(bestIoU(scene, template) >= 0.55)
        }
    }

    private fun template(name: String): Raster = listOf(
        File("src/main/res/drawable-nodpi/$name"),
        File("app/src/main/res/drawable-nodpi/$name")
    ).first { it.isFile }.inputStream().use(::raster)

    private fun bestIoU(scene: Raster, template: Raster): Double {
        val templateInk = template.colors.map(::templateInk)
        var best = 0.0
        for (top in 0..scene.height - template.height) {
            for (left in 0..scene.width - template.width) {
                var intersection = 0
                var union = 0
                templateInk.indices.forEach { index ->
                    val x = left + index % template.width
                    val y = top + index / template.width
                    val scenePixel = sceneInk(scene.colors[y * scene.width + x])
                    val templatePixel = templateInk[index]
                    if (scenePixel || templatePixel) union++
                    if (scenePixel && templatePixel) intersection++
                }
                if (union > 0) best = maxOf(best, intersection.toDouble() / union)
            }
        }
        return best
    }

    private fun sceneInk(color: Int): Boolean {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return (red * 30 + green * 59 + blue * 11) / 100 < 180
    }

    private fun templateInk(color: Int): Boolean {
        val luminance = 0.2126 * linear(color shr 16 and 0xff) +
            0.7152 * linear(color shr 8 and 0xff) +
            0.0722 * linear(color and 0xff)
        return luminance * 255 < 180
    }

    private fun linear(channel: Int): Double = (channel / 255.0).let {
        if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
    }

    private fun raster(stream: InputStream): Raster {
        val image = Class.forName("javax.imageio.ImageIO")
            .getMethod("read", InputStream::class.java)
            .invoke(null, stream)
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val getRgb = image.javaClass.getMethod("getRGB", Int::class.java, Int::class.java)
        return Raster(width, height, IntArray(width * height) { index ->
            getRgb.invoke(image, index % width, index / width) as Int
        })
    }

    private data class Raster(val width: Int, val height: Int, val colors: IntArray)
}
