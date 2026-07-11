package com.paka.app

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.org.okapibarcode.backend.DataBarExpanded

/** Exercises the same verified Bitmap path used by manual entry and pass viewing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarcodeGenerationTest {

    private data class Sample(val format: PakaFormat, val data: String)

    @Test
    fun everySupportedFormatRendersAndVerifiesAtCommonPanelWidths() {
        val samples = listOf(
            Sample(PakaFormat.QR, "https://paka.invalid/render-test"),
            Sample(PakaFormat.AZTEC, "AZTEC-PASS-1234567890"),
            Sample(PakaFormat.PDF417, "PDF417-PASS-1234567890"),
            Sample(PakaFormat.DATA_MATRIX, "DATA-MATRIX-123"),
            Sample(PakaFormat.CODE128, "CODE128-123"),
            Sample(PakaFormat.CODE39, "PAKA-123"),
            Sample(PakaFormat.CODE93, "PAKA123"),
            Sample(PakaFormat.CODABAR, "A12345B"),
            Sample(PakaFormat.EAN13, "590123412345"),
            Sample(PakaFormat.EAN8, "5512345"),
            Sample(PakaFormat.UPCA, "04210000526"),
            Sample(PakaFormat.UPCE, "0425261"),
            Sample(PakaFormat.ITF, "12345678"),
            Sample(PakaFormat.DATABAR_EXPANDED, "(01)09010374000019(21)04696367404058"),
        )

        samples.forEach { sample ->
            PANEL_WIDTHS.forEach { targetWidth -> assertSampleAtWidth(sample, targetWidth) }
        }
    }

    @Test
    fun denseLatin1AztecAndPdf417RemainByteExact() {
        val payload = ByteArray(256) { it.toByte() }.toString(Charsets.ISO_8859_1)
        for (format in listOf(PakaFormat.AZTEC, PakaFormat.PDF417)) {
            val bitmap = Barcodes.generate(format, payload, LIGHT_PHONE_PANEL_WIDTH)
            assertNotNull("dense binary $format must render and verify byte-exactly", bitmap)
            requireNotNull(bitmap)
            try {
                assertTrue(bitmap.width <= LIGHT_PHONE_PANEL_WIDTH)
                assertExpectedHorizontalModuleGrid(Sample(format, payload), LIGHT_PHONE_PANEL_WIDTH, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun sparseAndDenseSquareCodesRenderExactlyInsideTheLightGridStage() {
        val targetWidth = BarcodeDisplay.targetWidthPx(
            PakaFormat.QR,
            LIGHT_PHONE_SCREEN_WIDTH,
            LIGHT_PHONE_DISPLAY_PANEL_WIDTH,
        )
        val samples = listOf(
            Sample(PakaFormat.QR, "https://paka.invalid/qr-stage"),
            Sample(PakaFormat.QR, "DENSE-QR-" + "0123456789ABCDEF".repeat(80)),
            Sample(PakaFormat.AZTEC, "AZTEC-STAGE-1234567890"),
            Sample(PakaFormat.AZTEC, ByteArray(256) { it.toByte() }.toString(Charsets.ISO_8859_1)),
        )

        assertEquals(LIGHT_PHONE_SQUARE_STAGE_WIDTH, targetWidth)
        samples.forEach { sample ->
            val bitmap = requireNotNull(Barcodes.generate(sample.format, sample.data, targetWidth))
            try {
                val moduleCount = rawModuleWidth(sample)
                assertEquals("${sample.format} must remain square", bitmap.width, bitmap.height)
                assertTrue("${sample.format} must fit its black stage", bitmap.width <= targetWidth)
                assertTrue(
                    "${sample.format} must use the largest whole-module size that fits",
                    targetWidth - bitmap.width < moduleCount,
                )
                assertExpectedHorizontalModuleGrid(sample, targetWidth, bitmap)
                assertExpectedVerticalModuleGrid(sample, targetWidth, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun renderCapAndCacheUseTheSameVerifiedBitmap() {
        val payload = "https://paka.invalid/cache/render-cap"
        val capped = Barcodes.generateCached(PakaFormat.QR, payload, MAX_RENDER_WIDTH)
        val overCap = Barcodes.generateCached(PakaFormat.QR, payload, MAX_RENDER_WIDTH * 2)

        assertNotNull(capped)
        assertSame("equivalent clamped requests must reuse the verified cache entry", capped, overCap)
        requireNotNull(capped)
        assertTrue(capped.width <= MAX_RENDER_WIDTH)
        assertExpectedHorizontalModuleGrid(Sample(PakaFormat.QR, payload), MAX_RENDER_WIDTH, capped)
    }

    @Test
    fun symbolWiderThanThePanelIsRejectedInsteadOfDownscaled() {
        val tooDenseForMinimumPanel = "A".repeat(80)
        assertNull(Barcodes.generate(PakaFormat.CODE39, tooDenseForMinimumPanel, MIN_RENDER_WIDTH))
    }

    @Test
    fun dataMatrixHasARealTwoModuleQuietZone() {
        val sample = Sample(PakaFormat.DATA_MATRIX, "DATA-MATRIX-QUIET-ZONE")
        val bitmap = requireNotNull(Barcodes.generate(sample.format, sample.data, LIGHT_PHONE_PANEL_WIDTH))
        try {
            val modulePx = bitmap.width / rawModuleWidth(sample)
            val quietPx = COMPACT_MATRIX_MARGIN_MODULES * modulePx
            for (offset in 0 until quietPx) {
                assertEquals(WHITE, bitmap.getPixel(offset, bitmap.height / 2))
                assertEquals(WHITE, bitmap.getPixel(bitmap.width - 1 - offset, bitmap.height / 2))
                assertEquals(WHITE, bitmap.getPixel(bitmap.width / 2, offset))
                assertEquals(WHITE, bitmap.getPixel(bitmap.width / 2, bitmap.height - 1 - offset))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun latin1RenderersRejectLossyUnicode() {
        val lossy = "pass-🙂"
        for (format in listOf(PakaFormat.AZTEC, PakaFormat.PDF417)) {
            assertNotNull(Barcodes.validationError(format, lossy))
            assertNull(Barcodes.generate(format, lossy, LIGHT_PHONE_PANEL_WIDTH))
        }
    }

    @Test
    fun rectangularDataMatrixKeepsItsNaturalAspectRatio() {
        val data = (1..80).firstNotNullOf { length ->
            val candidate = "A".repeat(length)
            val raw = MultiFormatWriter().encode(
                candidate,
                BarcodeFormat.DATA_MATRIX,
                1,
                1,
                expectedHints(BarcodeFormat.DATA_MATRIX),
            )
            candidate.takeIf { raw.width != raw.height }
        }
        val bitmap = requireNotNull(Barcodes.generate(PakaFormat.DATA_MATRIX, data, LIGHT_PHONE_PANEL_WIDTH))
        try {
            assertTrue("rectangular Data Matrix must not be padded to a square", bitmap.width != bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun pdf417UsesItsLandscapeSymbolGeometry() {
        val bitmap = requireNotNull(
            Barcodes.generate(PakaFormat.PDF417, "PDF417-LANDSCAPE-GEOMETRY", LIGHT_PHONE_PANEL_WIDTH),
        )
        try {
            assertTrue("PDF417 must be wider than it is tall", bitmap.width > bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertSampleAtWidth(sample: Sample, targetWidth: Int) {
        val bitmap = Barcodes.generate(sample.format, sample.data, targetWidth)
        assertNotNull("${sample.format} must render at $targetWidth px", bitmap)
        requireNotNull(bitmap)
        try {
            assertTrue(
                "${sample.format} output ${bitmap.width} must fit $targetWidth",
                bitmap.width <= targetWidth,
            )
            assertExpectedHorizontalModuleGrid(sample, targetWidth, bitmap)
            if (sample.format in MATRIX_FORMATS) assertExpectedVerticalModuleGrid(sample, targetWidth, bitmap)
            assertWhiteHorizontalEdges(sample.format, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertExpectedHorizontalModuleGrid(sample: Sample, targetWidth: Int, bitmap: Bitmap) {
        val moduleCount = rawModuleWidth(sample)
        val modulePx = targetWidth.coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH) / moduleCount
        assertTrue("${sample.format} must fit at least one pixel per module", modulePx >= 1)
        assertEquals(
            "${sample.format} width must contain only complete modules",
            moduleCount * modulePx,
            bitmap.width,
        )

        var transitions = 0
        val rowStep = (bitmap.height / GRID_SAMPLE_ROWS).coerceAtLeast(1)
        for (y in 0 until bitmap.height step rowStep) {
            var previous = bitmap.getPixel(0, y)
            for (x in 1 until bitmap.width) {
                val current = bitmap.getPixel(x, y)
                if (current != previous) {
                    assertEquals(
                        "${sample.format} transition at x=$x is off its $modulePx px grid",
                        0,
                        x % modulePx,
                    )
                    transitions += 1
                    previous = current
                }
            }
        }
        assertTrue("${sample.format} bitmap must contain black/white transitions", transitions > 0)
    }

    private fun assertExpectedVerticalModuleGrid(sample: Sample, targetWidth: Int, bitmap: Bitmap) {
        val modulePx = targetWidth.coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH) / rawModuleWidth(sample)
        var transitions = 0
        val columnStep = (bitmap.width / GRID_SAMPLE_ROWS).coerceAtLeast(1)
        for (x in 0 until bitmap.width step columnStep) {
            var previous = bitmap.getPixel(x, 0)
            for (y in 1 until bitmap.height) {
                val current = bitmap.getPixel(x, y)
                if (current != previous) {
                    assertEquals(
                        "${sample.format} transition at y=$y is off its $modulePx px grid",
                        0,
                        y % modulePx,
                    )
                    transitions += 1
                    previous = current
                }
            }
        }
        assertTrue("${sample.format} bitmap must contain vertical transitions", transitions > 0)
    }

    private fun assertWhiteHorizontalEdges(format: PakaFormat, bitmap: Bitmap) {
        for (y in 0 until bitmap.height) {
            assertEquals("$format left edge must be quiet", WHITE, bitmap.getPixel(0, y))
            assertEquals("$format right edge must be quiet", WHITE, bitmap.getPixel(bitmap.width - 1, y))
        }
    }

    private fun rawModuleWidth(sample: Sample): Int {
        if (sample.format == PakaFormat.DATABAR_EXPANDED) {
            val symbol = DataBarExpanded().apply {
                setStacked(true)
                setPreferredColumns(4)
                content = sample.data.replace(Regex("\\((\\d{2,4})\\)"), "[$1]")
            }
            return symbol.width + DATABAR_QUIET_MODULES * 2
        }

        val format = sample.format.zxingFormat()
        val probeWidth = if (format == BarcodeFormat.PDF_417) PDF417_LANDSCAPE_PROBE_WIDTH else 1
        val matrix = MultiFormatWriter().encode(sample.data, format, probeWidth, 1, expectedHints(format))
        return when {
            format == BarcodeFormat.AZTEC || format == BarcodeFormat.DATA_MATRIX ->
                matrix.width + COMPACT_MATRIX_MARGIN_MODULES * 2
            format in LINEAR_FORMATS -> matrix.width + LINEAR_MARGIN_MODULES * 2
            else -> matrix.width
        }
    }

    private fun expectedHints(format: BarcodeFormat): Map<EncodeHintType, Any> = buildMap {
        put(
            EncodeHintType.MARGIN,
            when (format) {
                BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417 -> 4
                BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX -> 2
                else -> 0
            },
        )
        when (format) {
            BarcodeFormat.AZTEC, BarcodeFormat.PDF_417 -> put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX -> put(EncodeHintType.CHARACTER_SET, "UTF-8")
            else -> Unit
        }
        if (format == BarcodeFormat.QR_CODE) put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
    }

    private fun PakaFormat.zxingFormat(): BarcodeFormat =
        checkNotNull(ZXING_FORMATS[this]) { "DataBar uses OkapiBarcode" }

    private companion object {
        const val MIN_RENDER_WIDTH = 240
        const val MANUAL_CHECK_WIDTH = 320
        const val LIGHT_PHONE_SCREEN_WIDTH = 1080
        const val LIGHT_PHONE_DISPLAY_PANEL_WIDTH = 984
        const val LIGHT_PHONE_SQUARE_STAGE_WIDTH = 920
        const val LIGHT_PHONE_PANEL_WIDTH = 1048
        const val MAX_RENDER_WIDTH = 2160
        const val GRID_SAMPLE_ROWS = 48
        const val DATABAR_QUIET_MODULES = 4
        const val COMPACT_MATRIX_MARGIN_MODULES = 2
        const val LINEAR_MARGIN_MODULES = 10
        const val PDF417_LANDSCAPE_PROBE_WIDTH = 2
        const val WHITE = -0x1
        val PANEL_WIDTHS = intArrayOf(MANUAL_CHECK_WIDTH, LIGHT_PHONE_PANEL_WIDTH)
        val MATRIX_FORMATS = setOf(PakaFormat.QR, PakaFormat.AZTEC, PakaFormat.PDF417, PakaFormat.DATA_MATRIX)
        val LINEAR_FORMATS = setOf(
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93,
            BarcodeFormat.CODABAR,
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E,
            BarcodeFormat.ITF,
        )
        val ZXING_FORMATS = mapOf(
            PakaFormat.QR to BarcodeFormat.QR_CODE,
            PakaFormat.AZTEC to BarcodeFormat.AZTEC,
            PakaFormat.PDF417 to BarcodeFormat.PDF_417,
            PakaFormat.DATA_MATRIX to BarcodeFormat.DATA_MATRIX,
            PakaFormat.CODE128 to BarcodeFormat.CODE_128,
            PakaFormat.CODE39 to BarcodeFormat.CODE_39,
            PakaFormat.CODE93 to BarcodeFormat.CODE_93,
            PakaFormat.CODABAR to BarcodeFormat.CODABAR,
            PakaFormat.EAN13 to BarcodeFormat.EAN_13,
            PakaFormat.EAN8 to BarcodeFormat.EAN_8,
            PakaFormat.UPCA to BarcodeFormat.UPC_A,
            PakaFormat.UPCE to BarcodeFormat.UPC_E,
            PakaFormat.ITF to BarcodeFormat.ITF,
        )
    }
}
