package com.paka.app

import android.graphics.Bitmap
import android.util.LruCache
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.MessageDigest
import java.util.Locale

/** Every symbology Paka can render. */
enum class PakaFormat(private val displayLabel: String) {
    QR("QR"),
    AZTEC("Aztec"),
    PDF417("PDF417"),
    DATA_MATRIX("Data Matrix"),
    CODE128("Code 128"),
    CODE39("Code 39"),
    CODE93("Code 93"),
    CODABAR("Codabar"),
    EAN13("EAN-13"),
    EAN8("EAN-8"),
    UPCA("UPC-A"),
    UPCE("UPC-E"),
    ITF("ITF"),
    DATABAR_EXPANDED("GS1 DataBar"),
    ;

    internal fun label(): String = displayLabel
}

private val SQUARE_FORMATS = setOf(PakaFormat.QR, PakaFormat.AZTEC)
private val LINEAR_ZXING_FORMATS = setOf(
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
private const val ZXING_PROBE_SIZE = 1
private const val PDF417_LANDSCAPE_PROBE_WIDTH = 2
private const val QR_MARGIN_MODULES = 4
private const val COMPACT_MATRIX_MARGIN_MODULES = 2
private const val LINEAR_MARGIN_MODULES = 10
private const val QUIET_ZONE_SIDE_COUNT = 2
private const val BLACK_PIXEL = 0xFF000000.toInt()
private const val WHITE_PIXEL = 0xFFFFFFFF.toInt()

private fun PakaFormat.zxing(): BarcodeFormat? = when (this) {
    PakaFormat.QR -> BarcodeFormat.QR_CODE
    PakaFormat.AZTEC -> BarcodeFormat.AZTEC
    PakaFormat.PDF417 -> BarcodeFormat.PDF_417
    PakaFormat.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    PakaFormat.CODE128 -> BarcodeFormat.CODE_128
    PakaFormat.CODE39 -> BarcodeFormat.CODE_39
    PakaFormat.CODE93 -> BarcodeFormat.CODE_93
    PakaFormat.CODABAR -> BarcodeFormat.CODABAR
    PakaFormat.EAN13 -> BarcodeFormat.EAN_13
    PakaFormat.EAN8 -> BarcodeFormat.EAN_8
    PakaFormat.UPCA -> BarcodeFormat.UPC_A
    PakaFormat.UPCE -> BarcodeFormat.UPC_E
    PakaFormat.ITF -> BarcodeFormat.ITF
    PakaFormat.DATABAR_EXPANDED -> null // handled by OkapiBarcode
}

internal object Barcodes {
    private const val CACHE_KIB = 12 * 1024
    // Render cap. Raised beyond a typical panel so an enlarged/zoom view can be
    // drawn at native resolution instead of upscaling a smaller bitmap.
    private const val MAX_RENDER_PX = 2160
    private val bitmapCache = object : LruCache<String, Bitmap>(CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val digitLengths = mapOf(
        PakaFormat.EAN13 to Triple(12, 13, "EAN-13"),
        PakaFormat.EAN8 to Triple(7, 8, "EAN-8"),
        PakaFormat.UPCA to Triple(11, 12, "UPC-A"),
        PakaFormat.UPCE to Triple(7, 8, "UPC-E"),
    )

    fun validationError(format: PakaFormat, data: String): LocalizedMessage? {
        if (data.isBlank()) return LocalizedMessage(R.string.validation_required)
        digitLengths[format]?.let { (short, full, label) ->
            return digitLengthError(data, short, full, label)
        }
        return when (format) {
            PakaFormat.ITF -> itfValidationError(data)
            PakaFormat.CODE39 -> code39ValidationError(data)
            PakaFormat.CODABAR -> codabarValidationError(data)
            PakaFormat.AZTEC, PakaFormat.PDF417 -> latin1ValidationError(format, data)
            else -> null
        }
    }

    private fun itfValidationError(data: String): LocalizedMessage? = when {
        !data.all(Char::isDigit) -> LocalizedMessage(R.string.validation_digits_only, listOf("ITF"))
        data.length % 2 != 0 -> LocalizedMessage(R.string.validation_itf_even)
        else -> null
    }

    private fun code39ValidationError(data: String): LocalizedMessage? =
        if (
            data.uppercase(Locale.ROOT) != data ||
            data.any { it !in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%" }
        ) {
            LocalizedMessage(R.string.validation_code39)
        } else {
            null
        }

    private fun codabarValidationError(data: String): LocalizedMessage? =
        if (
            data.length < 2 ||
            data.first().uppercaseChar() !in "ABCD" ||
            data.last().uppercaseChar() !in "ABCD"
        ) {
            LocalizedMessage(R.string.validation_codabar)
        } else {
            null
        }

    private fun latin1ValidationError(format: PakaFormat, data: String): LocalizedMessage? =
        if (Charsets.ISO_8859_1.newEncoder().canEncode(data)) {
            null
        } else {
            LocalizedMessage(R.string.validation_latin1, listOf(format.label()))
        }

    /** Render at the actual available display width so modules are never smoothly rescaled. */
    fun generate(format: PakaFormat, data: String, targetWidthPx: Int = 960): Bitmap? {
        if (validationError(format, data) != null) return null
        val width = targetWidthPx.coerceIn(240, MAX_RENDER_PX)
        return if (format == PakaFormat.DATABAR_EXPANDED) {
            generateDataBar(data, width)
        } else {
            val zx = format.zxing() ?: return null
            val rawMatrix = zxingRawMatrix(zx, data) ?: return null
            // A symbol that cannot fit at one pixel per module would be scaled
            // by Compose and lose the exact module grid. Refuse it instead.
            if (rawMatrix.width > width) return null
            // Snap to an exact integer multiple of the module count: every module
            // is identical width and the quiet zone carries no leftover pixels.
            val snapped = snappedWidth(rawMatrix.width, width)
            val modulePx = snapped / rawMatrix.width
            val height = when {
                format in SQUARE_FORMATS -> snapped
                format == PakaFormat.DATA_MATRIX -> rawMatrix.height * modulePx
                format == PakaFormat.PDF417 -> rawMatrix.height * modulePx
                else -> (snapped * 0.30f).toInt().coerceAtLeast(180)
            }
            generateRawMatrix(rawMatrix, snapped, height, format, data)
        }
    }

    /** Bounded cache for passes already verified by [generate]. */
    fun generateCached(format: PakaFormat, data: String, targetWidthPx: Int = 960): Bitmap? {
        val width = targetWidthPx.coerceIn(240, MAX_RENDER_PX)
        val key = cacheKey(format, data, width)
        synchronized(bitmapCache) { bitmapCache.get(key) }?.let { return it }
        val generated = generate(format, data, width) ?: return null
        synchronized(bitmapCache) {
            bitmapCache.get(key)?.let { existing ->
                generated.recycle()
                return existing
            }
            bitmapCache.put(key, generated)
        }
        return generated
    }

    /** Largest exact integer-module width that fits [targetWidth]. */
    internal fun snappedWidth(moduleCount: Int, targetWidth: Int): Int {
        require(moduleCount in 1..targetWidth) { "Symbol modules must fit the target width" }
        val modulePx = targetWidth / moduleCount
        return moduleCount * modulePx
    }
}

private fun digitLengthError(data: String, short: Int, full: Int, label: String): LocalizedMessage? = when {
    !data.all(Char::isDigit) -> LocalizedMessage(R.string.validation_digits_only, listOf(label))
    data.length != short && data.length != full ->
        LocalizedMessage(R.string.validation_length, listOf(label, short, full))
    else -> null
}

private fun cacheKey(format: PakaFormat, data: String, width: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(data.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "${format.name}:$width:$digest"
}

/** Encoder hints shared by the raw module-count probe and the final render. */
private fun zxingHints(format: BarcodeFormat): HashMap<EncodeHintType, Any> {
    val hints = HashMap<EncodeHintType, Any>()
    hints[EncodeHintType.MARGIN] = when (format) {
        BarcodeFormat.QR_CODE -> QR_MARGIN_MODULES
        BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX -> COMPACT_MATRIX_MARGIN_MODULES
        BarcodeFormat.PDF_417 -> QR_MARGIN_MODULES
        // OneDimensionalCodeWriter treats MARGIN as a total allowance rather
        // than a per-side quiet zone. Pad linear symbols explicitly below.
        else -> 0
    }
    // Binary payloads (e.g. a KlimaTicket Aztec) map bytes 1:1 via Latin-1.
    when (format) {
        BarcodeFormat.AZTEC, BarcodeFormat.PDF_417 -> hints[EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX -> hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        else -> Unit
    }
    if (format == BarcodeFormat.QR_CODE) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    return hints
}

/** Raw module matrix (incl. quiet zone); reused for exact integer scaling. */
private fun zxingRawMatrix(format: BarcodeFormat, data: String): BitMatrix? = try {
    val probeWidth = if (format == BarcodeFormat.PDF_417) PDF417_LANDSCAPE_PROBE_WIDTH else ZXING_PROBE_SIZE
    MultiFormatWriter().encode(data, format, probeWidth, ZXING_PROBE_SIZE, zxingHints(format))
        .takeIf { it.width > 0 && it.height > 0 }
        ?.let { matrix ->
            // AztecWriter and DataMatrixWriter ignore EncodeHintType.MARGIN.
            // Add the requested quiet zone explicitly without changing the
            // symbol's natural (possibly rectangular) aspect ratio.
            when {
                format == BarcodeFormat.AZTEC || format == BarcodeFormat.DATA_MATRIX ->
                    matrix.withQuietZone(COMPACT_MATRIX_MARGIN_MODULES)
                format in LINEAR_ZXING_FORMATS -> matrix.withHorizontalQuietZone(LINEAR_MARGIN_MODULES)
                else -> matrix
            }
        }
} catch (_: WriterException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun BitMatrix.withQuietZone(quietModules: Int): BitMatrix {
    return BitMatrix(
        width + quietModules * QUIET_ZONE_SIDE_COUNT,
        height + quietModules * QUIET_ZONE_SIDE_COUNT,
    ).also { padded ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (get(x, y)) padded.set(quietModules + x, quietModules + y)
            }
        }
    }
}

private fun BitMatrix.withHorizontalQuietZone(quietModules: Int): BitMatrix {
    return BitMatrix(width + quietModules * QUIET_ZONE_SIDE_COUNT, height).also { padded ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (get(x, y)) padded.set(quietModules + x, y)
            }
        }
    }
}

private fun generateRawMatrix(
    raw: BitMatrix,
    width: Int,
    height: Int,
    format: PakaFormat,
    data: String,
): Bitmap? {
    val modulePx = width / raw.width
    if (modulePx < 1) return null
    val stretchVertically = format !in SQUARE_FORMATS &&
        format != PakaFormat.DATA_MATRIX &&
        format != PakaFormat.PDF417
    val pixels = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val rawX = x / modulePx
        val rawY = if (stretchVertically) y * raw.height / height else y / modulePx
        if (raw.get(rawX, rawY)) BLACK_PIXEL else WHITE_PIXEL
    }
    return verifiedBitmap(width, height, pixels, format, data)
}
