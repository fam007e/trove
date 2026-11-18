package com.trove.feature.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrovePdfTest {

    @Test
    fun testIsPdfDetection() {
        assertTrue(TrovePdfDocument.isPdf("book.pdf"))
        assertTrue(TrovePdfDocument.isPdf("MANUAL.PDF"))
        assertTrue(TrovePdfDocument.isPdf("document.file.pdf"))

        assertFalse(TrovePdfDocument.isPdf("image.png"))
        assertFalse(TrovePdfDocument.isPdf("archive.zip"))
        assertFalse(TrovePdfDocument.isPdf("text.txt"))
    }
}
