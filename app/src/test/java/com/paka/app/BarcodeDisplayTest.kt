package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeDisplayTest {

    @Test
    fun qrAndAztecUseTheLightGridStage() {
        for (format in listOf(PakaFormat.QR, PakaFormat.AZTEC)) {
            assertTrue(BarcodeDisplay.usesCompactSquareStage(format))
            assertEquals(
                LIGHT_PHONE_SQUARE_STAGE_WIDTH,
                BarcodeDisplay.targetWidthPx(format, LIGHT_PHONE_SCREEN_WIDTH, STANDARD_PANEL_WIDTH),
            )
        }
    }

    @Test
    fun everyOtherFormatKeepsTheExistingPanelWidth() {
        PakaFormat.entries
            .filterNot(BarcodeDisplay::usesCompactSquareStage)
            .forEach { format ->
                assertFalse(BarcodeDisplay.usesCompactSquareStage(format))
                assertEquals(
                    "$format must retain the established barcode presentation",
                    STANDARD_PANEL_WIDTH,
                    BarcodeDisplay.targetWidthPx(format, LIGHT_PHONE_SCREEN_WIDTH, STANDARD_PANEL_WIDTH),
                )
            }
    }

    @Test
    fun squareStageNeverExceedsTheAvailablePanel() {
        assertEquals(640, BarcodeDisplay.targetWidthPx(PakaFormat.QR, LIGHT_PHONE_SCREEN_WIDTH, 640))
        assertEquals(640, BarcodeDisplay.targetWidthPx(PakaFormat.AZTEC, LIGHT_PHONE_SCREEN_WIDTH, 640))
    }

    @Test
    fun gridCalculationRoundsDown() {
        assertEquals(920, BarcodeDisplay.targetWidthPx(PakaFormat.QR, 1081, 984))
    }

    @Test
    fun invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BarcodeDisplay.targetWidthPx(PakaFormat.QR, 0, STANDARD_PANEL_WIDTH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BarcodeDisplay.targetWidthPx(PakaFormat.QR, LIGHT_PHONE_SCREEN_WIDTH, 0)
        }
    }

    private companion object {
        const val LIGHT_PHONE_SCREEN_WIDTH = 1080
        const val STANDARD_PANEL_WIDTH = 984
        const val LIGHT_PHONE_SQUARE_STAGE_WIDTH = 920
    }
}
