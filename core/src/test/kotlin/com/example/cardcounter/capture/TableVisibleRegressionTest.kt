package com.example.cardcounter.capture

import com.example.cardcounter.vision.ArgbRaster
import com.example.cardcounter.vision.Raster
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：8385 录屏里手牌摆开后，老探针被牌面压住，牌桌被误判成“离开牌桌”，3 秒后记牌面板收起。
 * 资源 core/src/test/resources/table_visible/ 是录屏第 68.5 / 71.3 / 80.0 秒三帧，只留探针点周围的小块，其余涂黑。
 * 这三帧都在牌桌上，必须判成可见，并且命中数比阈值多留至少 1 个点的余量。
 */
class TableVisibleRegressionTest {
    private fun loadRaster(id: String): Raster {
        val stream = requireNotNull(javaClass.getResourceAsStream("/table_visible/$id.png")) { "缺少帧 $id" }
        val image = stream.use { ImageIO.read(it) }
        val pixels = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) and 0xffffff }
        return ArgbRaster(pixels, image.width, image.height)
    }

    private val ids = listOf("f_68_5", "f_71_3", "f_80_0")

    @Test
    fun recordedTableFramesAreVisible() {
        ids.forEach { id ->
            val raster = loadRaster(id)
            val readings = tableProbeReadings(raster)
            val detail = readings.joinToString(" ") { it.describe() }
            assertTrue("$id 应判成牌桌可见，探针读数：$detail", isTableVisible(raster))
            assertTrue(
                "$id 命中数 ${readings.count { it.green }}/${readings.size} 没有比阈值 $TABLE_GREEN_MIN_HITS 多留余量：$detail",
                readings.count { it.green } > TABLE_GREEN_MIN_HITS
            )
        }
    }

    @Test
    fun probesAreReadFromTheSameCanvasPoints() {
        assertEquals(7, TABLE_GREEN_PROBES.size)
        ids.forEach { id ->
            assertEquals(TABLE_GREEN_PROBES.size, tableProbeReadings(loadRaster(id)).size)
        }
    }
}
