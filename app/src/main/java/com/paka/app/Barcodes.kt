package com.paka.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import uk.org.okapibarcode.backend.DataBarExpanded
import java.security.MessageDigest

/** Every symbology Paka can render. */
enum class PakaFormat {
    QR, AZTEC, PDF417, DATA_MATRIX,
    CODE128, CODE39, CODE93, CODABAR, EAN13, EAN8, UPCA, UPCE, ITF,
    DATABAR_EXPANDED,
}

private val SQUARE_FORMATS = setOf(PakaFormat.QR, PakaFormat.AZTEC, PakaFormat.DATA_MATRIX)

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

object Barcodes {
    private const val CACHE_KIB = 12 * 1024
    // Render cap. Raised beyond a typical panel so an enlarged/zoom view can be
    // drawn at native resolution instead of upscaling a smaller bitmap.
    private const val MAX_RENDER_PX = 2160
    private val bitmapCache = object : LruCache<String, Bitmap>(CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun validationError(format: PakaFormat, data: String): String? {
        if (data.isBlank()) return "Code content is required"
        return when (format) {
            PakaFormat.EAN13 -> digitLengthError(data, 12, 13, "EAN-13")
            PakaFormat.EAN8 -> digitLengthError(data, 7, 8, "EAN-8")
            PakaFormat.UPCA -> digitLengthError(data, 11, 12, "UPC-A")
            PakaFormat.UPCE -> digitLengthError(data, 7, 8, "UPC-E")
            PakaFormat.ITF -> when {
                !data.all(Char::isDigit) -> "ITF accepts digits only"
                data.length % 2 != 0 -> "ITF requires an even number of digits"
                else -> null
            }
            PakaFormat.CODE39 -> if (data.uppercase() != data || data.any { it !in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%" }) {
                "Code 39 contains unsupported characters"
            } else null
            PakaFormat.CODABAR -> if (data.length < 2 || data.first().uppercaseChar() !in "ABCD" || data.last().uppercaseChar() !in "ABCD") {
                "Codabar must start and end with A, B, C, or D"
            } else null
            else -> null
        }
    }

    private fun digitLengthError(data: String, short: Int, full: Int, label: String): String? = when {
        !data.all(Char::isDigit) -> "$label accepts digits only"
        data.length != short && data.length != full -> "$label requires $short or $full digits"
        else -> null
    }

    /** Render at the actual available display width so modules are never smoothly rescaled. */
    fun generate(format: PakaFormat, data: String, targetWidthPx: Int = 960): Bitmap? {
        if (validationError(format, data) != null) return null
        val width = targetWidthPx.coerceIn(240, MAX_RENDER_PX)
        return if (format == PakaFormat.DATABAR_EXPANDED) {
            generateDataBar(data, width)
        } else {
            val zx = format.zxing() ?: return null
            // Snap to an exact integer multiple of the module count: every module
            // is identical width and the quiet zone carries no leftover pixels.
            val modules = zxingModuleWidth(zx, data) ?: return null
            val snapped = snappedWidth(modules, width)
            val height = when {
                format in SQUARE_FORMATS -> snapped
                format == PakaFormat.PDF417 -> (snapped * 0.42f).toInt().coerceAtLeast(240)
                else -> (snapped * 0.30f).toInt().coerceAtLeast(180)
            }
            generateZxing(zx, data, snapped, height)
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
            BarcodeFormat.QR_CODE -> 4
            BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX -> 2
            BarcodeFormat.PDF_417 -> 4
            else -> 10
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

    /** Module count (incl. quiet zone) of the raw symbol; drives module snapping. */
    private fun zxingModuleWidth(format: BarcodeFormat, data: String): Int? = try {
        MultiFormatWriter().encode(data, format, 1, 1, zxingHints(format)).width.takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    /** Largest exact integer-module width that fits [targetWidth]. */
    internal fun snappedWidth(moduleCount: Int, targetWidth: Int): Int {
        val modulePx = (targetWidth / moduleCount).coerceAtLeast(1)
        return moduleCount * modulePx
    }

    private fun generateZxing(format: BarcodeFormat, data: String, width: Int, height: Int): Bitmap? {
        return try {
            val matrix = MultiFormatWriter().encode(data, format, width, height, zxingHints(format))
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h) { i ->
                if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            if (!BarcodePayloadVerifier.verify(w, h, pixels, format.paka(), data)) return null
            Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * GS1 DataBar Expanded Stacked via OkapiBarcode (pure Java, no Google, no native).
     * Data arrives as HRI "(01)…(21)…" and is converted to Okapi's "[01]…[21]…" form.
     * Two columns lays a typical jö payload out in two rows, matching the printed card.
     */
    // Parenthesized HRI may contain alphanumeric variable-length values (for
    // example batch AI 10 and serial AI 21). Preserve those values verbatim.
    private fun gs1ToBrackets(raw: String): String {
        val sb = StringBuilder()
        Regex("""\((\d{2,4})\)(.*?)(?=\(\d{2,4}\)|$)""", RegexOption.DOT_MATCHES_ALL).findAll(raw).forEach {
            val value = it.groupValues[2].trimEnd('\u001D')
            sb.append('[').append(it.groupValues[1]).append(']').append(value)
        }
        return if (sb.isNotEmpty()) sb.toString() else raw
    }

    private fun generateDataBar(data: String, targetWidthPx: Int): Bitmap? {
        return try {
            val symbol = DataBarExpanded()
            symbol.setStacked(true)
            symbol.setPreferredColumns(4) // 4 data columns lays a jö payload out in 2 rows
            symbol.content = gs1ToBrackets(data)

            val quietModules = 4
            val modulePx = (targetWidthPx / (symbol.width + quietModules * 2)).coerceAtLeast(1)
            val marginPx = quietModules * modulePx
            val w = symbol.width * modulePx + marginPx * 2
            val h = symbol.height * modulePx + marginPx * 2
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
            for (r in symbol.rectangles) {
                val left = (r.x * modulePx + marginPx).toFloat()
                val top = (r.y * modulePx + marginPx).toFloat()
                canvas.drawRect(left, top, left + (r.width * modulePx).toFloat(), top + (r.height * modulePx).toFloat(), paint)
            }
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            if (!BarcodePayloadVerifier.verify(w, h, pixels, PakaFormat.DATABAR_EXPANDED, data)) {
                bmp.recycle()
                return null
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}

private fun BarcodeFormat.paka(): PakaFormat = when (this) {
    BarcodeFormat.QR_CODE -> PakaFormat.QR
    BarcodeFormat.AZTEC -> PakaFormat.AZTEC
    BarcodeFormat.PDF_417 -> PakaFormat.PDF417
    BarcodeFormat.DATA_MATRIX -> PakaFormat.DATA_MATRIX
    BarcodeFormat.CODE_128 -> PakaFormat.CODE128
    BarcodeFormat.CODE_39 -> PakaFormat.CODE39
    BarcodeFormat.CODE_93 -> PakaFormat.CODE93
    BarcodeFormat.CODABAR -> PakaFormat.CODABAR
    BarcodeFormat.EAN_13 -> PakaFormat.EAN13
    BarcodeFormat.EAN_8 -> PakaFormat.EAN8
    BarcodeFormat.UPC_A -> PakaFormat.UPCA
    BarcodeFormat.UPC_E -> PakaFormat.UPCE
    BarcodeFormat.ITF -> PakaFormat.ITF
    else -> error("Unsupported Paka barcode format: $this")
}
