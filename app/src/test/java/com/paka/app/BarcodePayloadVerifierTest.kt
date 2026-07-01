package com.paka.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.org.okapibarcode.backend.DataBarExpanded

class BarcodePayloadVerifierTest {
    @Test
    fun binaryAztecComparisonIsByteExact() {
        val payload = byteArrayOf(0, 1, 127, 128.toByte(), 255.toByte()).toString(Charsets.ISO_8859_1)
        assertTrue(BarcodePayloadVerifier.payloadMatches(PakaFormat.AZTEC, payload, payload))
        assertFalse(BarcodePayloadVerifier.payloadMatches(PakaFormat.AZTEC, payload, payload.dropLast(1) + "x"))
    }

    @Test
    fun retailCheckDigitMayBeAddedByWriter() {
        assertTrue(BarcodePayloadVerifier.payloadMatches(PakaFormat.EAN13, "123456789012", "1234567890128"))
        assertFalse(BarcodePayloadVerifier.payloadMatches(PakaFormat.EAN13, "123456789012", "1234567899998"))
    }

    @Test
    fun gs1HumanReadableFormattingDoesNotChangePayload() {
        val expected = "(01)09010374000019(21)04696367404058"
        val decoded = "01090103740000192104696367404058"
        assertTrue(BarcodePayloadVerifier.payloadMatches(PakaFormat.DATABAR_EXPANDED, expected, decoded))
    }

    @Test
    fun renderedBinaryAztecDecodesToExactPayload() {
        val payload = ByteArray(256) { it.toByte() }.toString(Charsets.ISO_8859_1)
        assertRenderedPayload(
            PakaFormat.AZTEC,
            BarcodeFormat.AZTEC,
            payload,
            mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1", EncodeHintType.MARGIN to 2),
        )
    }

    @Test
    fun renderedUnicodeQrDecodesToExactPayload() {
        assertRenderedPayload(
            PakaFormat.QR,
            BarcodeFormat.QR_CODE,
            "Paka · Wien · 2026",
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4),
        )
    }

    @Test
    fun renderedStackedDataBarDecodesToSameGs1Payload() {
        val payload = "(01)09010374000019(21)04696367404058284919(10)01653027960"
        val symbol = DataBarExpanded().apply {
            setStacked(true)
            setPreferredColumns(4)
            content = payload.replace(Regex("\\((\\d{2,4})\\)"), "[$1]")
        }
        val quietModules = 4
        val modulePx = (960 / (symbol.width + quietModules * 2)).coerceAtLeast(1)
        val marginPx = quietModules * modulePx
        val width = symbol.width * modulePx + marginPx * 2
        val height = symbol.height * modulePx + marginPx * 2
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        symbol.rectangles.forEach { rectangle ->
            val left = (rectangle.x * modulePx).toInt() + marginPx
            val top = (rectangle.y * modulePx).toInt() + marginPx
            val right = left + (rectangle.width * modulePx).toInt()
            val bottom = top + (rectangle.height * modulePx).toInt()
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = 0xFF000000.toInt()
        }
        assertTrue(BarcodePayloadVerifier.verify(width, height, pixels, PakaFormat.DATABAR_EXPANDED, payload))
    }

    @Test
    fun everyZxingBackedFormatPassesItsRenderCheck() {
        data class Sample(val paka: PakaFormat, val zxing: BarcodeFormat, val value: String, val square: Boolean = false)
        val samples = listOf(
            Sample(PakaFormat.QR, BarcodeFormat.QR_CODE, "https://paka.invalid/test", true),
            Sample(PakaFormat.AZTEC, BarcodeFormat.AZTEC, "binary\u0000aztec", true),
            Sample(PakaFormat.PDF417, BarcodeFormat.PDF_417, "PDF417 payload"),
            Sample(PakaFormat.DATA_MATRIX, BarcodeFormat.DATA_MATRIX, "Data Matrix", true),
            Sample(PakaFormat.CODE128, BarcodeFormat.CODE_128, "ABC123"),
            Sample(PakaFormat.CODE39, BarcodeFormat.CODE_39, "ABC-123"),
            Sample(PakaFormat.CODE93, BarcodeFormat.CODE_93, "ABC123"),
            Sample(PakaFormat.CODABAR, BarcodeFormat.CODABAR, "A12345B"),
            Sample(PakaFormat.EAN13, BarcodeFormat.EAN_13, "590123412345"),
            Sample(PakaFormat.EAN8, BarcodeFormat.EAN_8, "5512345"),
            Sample(PakaFormat.UPCA, BarcodeFormat.UPC_A, "04210000526"),
            Sample(PakaFormat.UPCE, BarcodeFormat.UPC_E, "0425261"),
            Sample(PakaFormat.ITF, BarcodeFormat.ITF, "12345678"),
        )
        val failures = samples.filterNot { sample ->
            runCatching {
                val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to if (sample.square) 2 else 10)
                if (sample.paka in setOf(PakaFormat.AZTEC, PakaFormat.PDF417)) {
                    hints[EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
                }
                val height = if (sample.square) 960 else 300
                val matrix = MultiFormatWriter().encode(sample.value, sample.zxing, 960, height, hints)
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
                BarcodePayloadVerifier.verify(matrix.width, matrix.height, pixels, sample.paka, sample.value)
            }.getOrDefault(false)
        }
        assertTrue("Verification failed for ${failures.joinToString { it.paka.name }}", failures.isEmpty())
    }

    private fun assertRenderedPayload(
        pakaFormat: PakaFormat,
        zxingFormat: BarcodeFormat,
        payload: String,
        hints: Map<EncodeHintType, Any>,
    ) {
        val matrix = MultiFormatWriter().encode(payload, zxingFormat, 960, 960, hints)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertTrue(BarcodePayloadVerifier.verify(matrix.width, matrix.height, pixels, pakaFormat, payload))
    }
}
