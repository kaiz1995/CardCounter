package com.example.cardcounter.capture

import android.media.Image
import com.example.cardcounter.vision.Raster
import com.example.cardcounter.vision.RgbaRaster

/** ImageReader 的 RGBA_8888 帧 → [com.example.cardcounter.vision.ScreenScale] 能读的图；只在 image.close() 之前用。 */
internal fun Image.toRaster(): Raster {
    val plane = planes[0]
    return RgbaRaster(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
}
