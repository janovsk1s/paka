package com.paka.app

/**
 * Display-only sizing for square codes on the Light Phone III grid.
 *
 * QR and Aztec use a centred 23-column stage so their verified bitmap is
 * surrounded by black UI space instead of an almost edge-to-edge white panel.
 * Every other format keeps the existing panel width and presentation.
 */
internal object BarcodeDisplay {
    private const val LIGHT_GRID_COLUMNS = 27
    private const val SQUARE_STAGE_COLUMNS = 23

    fun usesCompactSquareStage(format: PakaFormat): Boolean =
        format == PakaFormat.QR || format == PakaFormat.AZTEC

    /**
     * [availableWidthPx] is the width of the container the code is shown in
     * (the full screen on the pass screens); the 23/27 ratio applies to it.
     * Prefetch call sites must derive both widths exactly like their display
     * site, or the prefetched bitmap misses the render cache and is wasted.
     */
    fun targetWidthPx(
        format: PakaFormat,
        availableWidthPx: Int,
        standardTargetWidthPx: Int,
    ): Int {
        require(availableWidthPx > 0) { "Available width must be positive" }
        require(standardTargetWidthPx > 0) { "Standard target width must be positive" }
        if (!usesCompactSquareStage(format)) return standardTargetWidthPx

        val gridTarget = (availableWidthPx.toLong() * SQUARE_STAGE_COLUMNS / LIGHT_GRID_COLUMNS).toInt()
        return minOf(gridTarget, standardTargetWidthPx).coerceAtLeast(1)
    }
}
