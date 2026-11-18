package com.trove.feature.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TroveBitmapPoolTest {

    @Test
    fun testIsImageDetection() {
        assertTrue(ImageDecoderUtils.isImage("photo.jpg"))
        assertTrue(ImageDecoderUtils.isImage("photo.PNG"))
        assertTrue(ImageDecoderUtils.isImage("sticker.webp"))
        assertTrue(ImageDecoderUtils.isImage("image.avif"))
        assertTrue(ImageDecoderUtils.isImage("graphic.jxl"))
        assertTrue(ImageDecoderUtils.isImage("document.tiff"))
        assertTrue(ImageDecoderUtils.isImage("camera.cr2"))
        assertTrue(ImageDecoderUtils.isImage("nikon.nef"))
        assertTrue(ImageDecoderUtils.isImage("sony.arw"))
        assertTrue(ImageDecoderUtils.isImage("adobe.dng"))

        assertFalse(ImageDecoderUtils.isImage("audio.mp3"))
        assertFalse(ImageDecoderUtils.isImage("archive.zip"))
        assertFalse(ImageDecoderUtils.isImage("document.pdf"))
    }

    @Test
    fun testCalculateInSampleSize() {
        // Exact fit
        assertEquals(1, ImageDecoderUtils.calculateInSampleSize(100, 100, 100, 100))

        // Half scale down
        assertEquals(2, ImageDecoderUtils.calculateInSampleSize(200, 200, 100, 100))

        // Quarter scale down (4x in dimensions)
        assertEquals(4, ImageDecoderUtils.calculateInSampleSize(400, 400, 100, 100))

        // Aspect ratio handling: only downsample when both dimensions are >= required
        assertEquals(2, ImageDecoderUtils.calculateInSampleSize(250, 500, 100, 100))
    }

    @Test
    fun testTroveBitmapPoolInitialState() {
        val pool = TroveBitmapPool(maxSizeBytes = 1024 * 1024)
        assertEquals(0L, pool.getCurrentSizeBytes())
        assertEquals(1024 * 1024L, pool.maxSizeBytes)
    }
}
