package com.paka.app

import android.graphics.Bitmap
import uk.org.okapibarcode.backend.DataBarExpanded
import uk.org.okapibarcode.backend.OkapiException

private const val DATABAR_PREFERRED_COLUMNS = 4
private const val DATABAR_QUIET_MODULES = 4
private const val QUIET_ZONE_SIDE_COUNT = 2
private const val BLACK_PIXEL = 0xFF000000.toInt()
private const val WHITE_PIXEL = 0xFFFFFFFF.toInt()

private data class PixelRectangle(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** GS1 DataBar Expanded Stacked rendered directly onto an integer module grid. */
internal fun generateDataBar(data: String, targetWidthPx: Int): Bitmap? = try {
    val symbol = DataBarExpanded()
    symbol.setStacked(true)
    symbol.setPreferredColumns(DATABAR_PREFERRED_COLUMNS)
    symbol.content = gs1ToBrackets(data)

    val moduleCount = symbol.width + DATABAR_QUIET_MODULES * QUIET_ZONE_SIDE_COUNT
    require(moduleCount <= targetWidthPx) { "DataBar is wider than the target" }
    val modulePx = targetWidthPx / moduleCount
    val marginPx = DATABAR_QUIET_MODULES * modulePx
    val width = symbol.width * modulePx + marginPx * QUIET_ZONE_SIDE_COUNT
    val height = symbol.height * modulePx + marginPx * QUIET_ZONE_SIDE_COUNT
    val pixels = IntArray(width * height) { WHITE_PIXEL }
    symbol.rectangles.forEach { rectangle ->
        fillRectangle(
            pixels = pixels,
            canvasWidth = width,
            bounds = PixelRectangle(
                left = (rectangle.x * modulePx).toInt() + marginPx,
                top = (rectangle.y * modulePx).toInt() + marginPx,
                right = ((rectangle.x + rectangle.width) * modulePx).toInt() + marginPx,
                bottom = ((rectangle.y + rectangle.height) * modulePx).toInt() + marginPx,
            ),
        )
    }
    verifiedBitmap(width, height, pixels, PakaFormat.DATABAR_EXPANDED, data)
} catch (_: OkapiException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun fillRectangle(
    pixels: IntArray,
    canvasWidth: Int,
    bounds: PixelRectangle,
) {
    for (y in bounds.top until bounds.bottom) {
        pixels.fill(BLACK_PIXEL, y * canvasWidth + bounds.left, y * canvasWidth + bounds.right)
    }
}

private fun gs1ToBrackets(raw: String): String {
    val bracketed = StringBuilder()
    Regex("""\((\d{2,4})\)(.*?)(?=\(\d{2,4}\)|$)""", RegexOption.DOT_MATCHES_ALL).findAll(raw).forEach {
        val value = it.groupValues[2].trimEnd('\u001D')
        bracketed.append('[').append(it.groupValues[1]).append(']').append(value)
    }
    return if (bracketed.isNotEmpty()) bracketed.toString() else raw
}

internal fun verifiedBitmap(
    width: Int,
    height: Int,
    pixels: IntArray,
    format: PakaFormat,
    data: String,
): Bitmap? {
    if (!BarcodePayloadVerifier.verify(width, height, pixels, format, data)) return null
    return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
