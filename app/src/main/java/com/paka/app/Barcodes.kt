package com.paka.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import uk.org.okapibarcode.backend.DataBarExpanded
import java.util.UUID

/** Every symbology Paka can render. */
enum class PakaFormat {
    QR, AZTEC, PDF417, DATA_MATRIX,
    CODE128, CODE39, CODE93, CODABAR, EAN13, EAN8, UPCA, UPCE, ITF,
    DATABAR_EXPANDED,
}

data class Card(
    val name: String,
    val data: String,
    val format: PakaFormat,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    val stack: String? = null,
)

private val TWO_D = setOf(PakaFormat.QR, PakaFormat.AZTEC, PakaFormat.DATA_MATRIX, PakaFormat.PDF417)

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

    /** Render [data] as [format] into a crisp black-on-white bitmap, or null on failure. */
    fun generate(format: PakaFormat, data: String, scale: Int = 3): Bitmap? {
        if (validationError(format, data) != null) return null
        return if (format == PakaFormat.DATABAR_EXPANDED) {
            generateDataBar(data)
        } else {
            generateZxing(format.zxing() ?: return null, data, format in TWO_D, scale)
        }
    }

    private fun generateZxing(format: BarcodeFormat, data: String, is2D: Boolean, scale: Int): Bitmap? {
        return try {
            val baseW = 320
            val baseH = if (is2D) 320 else 96
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = if (is2D) 2 else 8
            // Binary payloads (e.g. a KlimaTicket Aztec) map bytes 1:1 via Latin-1.
            when (format) {
                BarcodeFormat.AZTEC, BarcodeFormat.PDF_417 -> hints[EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX -> hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
                else -> Unit
            }
            val matrix = MultiFormatWriter().encode(data, format, baseW * scale, baseH * scale, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h) { i ->
                if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
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

    private fun generateDataBar(data: String, modulePx: Int = 5, marginPx: Int = 12): Bitmap? {
        return try {
            val symbol = DataBarExpanded()
            symbol.setStacked(true)
            symbol.setPreferredColumns(4) // 4 data columns lays a jö payload out in 2 rows
            symbol.content = gs1ToBrackets(data)

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
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
