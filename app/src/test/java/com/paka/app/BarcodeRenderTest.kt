package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Module-snapping keeps every rendered module an identical integer pixel width. */
class BarcodeRenderTest {

    @Test
    fun snapsToExactModuleMultiple() {
        // 41-module QR at 640 px -> 15 px per module -> 615, no leftover pixels.
        assertEquals(615, Barcodes.snappedWidth(41, 640))
        // 133-module Code 128 at 640 px -> 4 px per module -> 532.
        assertEquals(532, Barcodes.snappedWidth(133, 640))
    }

    @Test
    fun resultIsAlwaysAWholeNumberOfModulesAndFits() {
        for (modules in intArrayOf(21, 41, 77, 133, 185)) {
            for (target in 240..2160 step 29) {
                val w = Barcodes.snappedWidth(modules, target)
                assertEquals("uniform modules", 0, w % modules)
                if (modules <= target) assertTrue("never larger than target", w <= target)
            }
        }
    }

    @Test
    fun denserThanTargetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Barcodes.snappedWidth(700, 640)
        }
    }
}
