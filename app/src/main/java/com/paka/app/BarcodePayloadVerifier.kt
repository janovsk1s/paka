package com.paka.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import uk.org.okapibarcode.util.Gs1

/** Decodes rendered pixels again so Paka never presents an unchecked symbol. */
internal object BarcodePayloadVerifier {
    fun verify(width: Int, height: Int, pixels: IntArray, format: PakaFormat, expected: String): Boolean =
        runCatching {
            val source = RGBLuminanceSource(width, height, pixels)
            val hints = mutableMapOf<DecodeHintType, Any>(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to possibleFormats(format),
            )
            if (format == PakaFormat.DATA_MATRIX) hints[DecodeHintType.PURE_BARCODE] = true
            val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
            payloadMatches(format, expected, result.text)
        }.getOrDefault(false)

    internal fun payloadMatches(format: PakaFormat, expected: String, decoded: String): Boolean = when (format) {
        PakaFormat.AZTEC, PakaFormat.PDF417 -> {
            val expectedEncoder = Charsets.ISO_8859_1.newEncoder()
            val decodedEncoder = Charsets.ISO_8859_1.newEncoder()
            expectedEncoder.canEncode(expected) && decodedEncoder.canEncode(decoded) &&
                decoded.toByteArray(Charsets.ISO_8859_1)
                    .contentEquals(expected.toByteArray(Charsets.ISO_8859_1))
        }

        PakaFormat.EAN13, PakaFormat.EAN8, PakaFormat.UPCA, PakaFormat.UPCE ->
            decoded == expected ||
                (decoded.length == expected.length + 1 && decoded.startsWith(expected) && decoded.all(Char::isDigit))

        PakaFormat.CODABAR -> decoded == expected ||
            (expected.length >= 2 && decoded == expected.substring(1, expected.lastIndex))

        PakaFormat.DATABAR_EXPANDED -> runCatching {
            canonicalGs1(decoded) == canonicalGs1(expected)
        }.getOrDefault(false)
        else -> decoded == expected
    }

    private fun canonicalGs1(value: String): String {
        val clean = value.removePrefix("]e0")
        val bracketed = when {
            clean.contains('[') -> clean
            clean.contains('(') -> clean.replace(Regex("\\((\\d{2,4})\\)"), "[$1]")
            else -> return clean
        }
        // Okapi's GS1 table validates every AI and inserts a group separator
        // only after variable-length fields. Keeping those boundaries prevents
        // two different AI/value sequences from comparing equal.
        return Gs1.verify(bracketed, "\u001D")
    }

    private fun possibleFormats(format: PakaFormat): List<BarcodeFormat> = when (format) {
        PakaFormat.QR -> listOf(BarcodeFormat.QR_CODE)
        PakaFormat.AZTEC -> listOf(BarcodeFormat.AZTEC)
        PakaFormat.PDF417 -> listOf(BarcodeFormat.PDF_417)
        PakaFormat.DATA_MATRIX -> listOf(BarcodeFormat.DATA_MATRIX)
        PakaFormat.CODE128 -> listOf(BarcodeFormat.CODE_128)
        PakaFormat.CODE39 -> listOf(BarcodeFormat.CODE_39)
        PakaFormat.CODE93 -> listOf(BarcodeFormat.CODE_93)
        PakaFormat.CODABAR -> listOf(BarcodeFormat.CODABAR)
        PakaFormat.EAN13 -> listOf(BarcodeFormat.EAN_13)
        PakaFormat.EAN8 -> listOf(BarcodeFormat.EAN_8)
        PakaFormat.UPCA -> listOf(BarcodeFormat.UPC_A, BarcodeFormat.EAN_13)
        PakaFormat.UPCE -> listOf(BarcodeFormat.UPC_E)
        PakaFormat.ITF -> listOf(BarcodeFormat.ITF)
        PakaFormat.DATABAR_EXPANDED -> listOf(BarcodeFormat.RSS_EXPANDED)
    }
}
