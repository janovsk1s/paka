package com.paka.app

/** A corner of the crop selection. */
internal enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** What a drag that started at some point should do to the selection. */
internal sealed interface CropDrag {
    data class Corner(val handle: CropHandle) : CropDrag
    data object Move : CropDrag
}

/**
 * Crop selection in coordinates normalized to the photo (0..1 on both axes),
 * so it is independent of how large the photo is drawn on screen.
 */
internal data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun movedBy(dx: Float, dy: Float): CropRect {
        val clampedDx = dx.coerceIn(-left, 1f - right)
        val clampedDy = dy.coerceIn(-top, 1f - bottom)
        return CropRect(left + clampedDx, top + clampedDy, right + clampedDx, bottom + clampedDy)
    }

    fun resized(handle: CropHandle, dx: Float, dy: Float, minSize: Float): CropRect = when (handle) {
        CropHandle.TOP_LEFT -> copy(
            left = (left + dx).coerceIn(0f, right - minSize),
            top = (top + dy).coerceIn(0f, bottom - minSize),
        )
        CropHandle.TOP_RIGHT -> copy(
            right = (right + dx).coerceIn(left + minSize, 1f),
            top = (top + dy).coerceIn(0f, bottom - minSize),
        )
        CropHandle.BOTTOM_LEFT -> copy(
            left = (left + dx).coerceIn(0f, right - minSize),
            bottom = (bottom + dy).coerceIn(top + minSize, 1f),
        )
        CropHandle.BOTTOM_RIGHT -> copy(
            right = (right + dx).coerceIn(left + minSize, 1f),
            bottom = (bottom + dy).coerceIn(top + minSize, 1f),
        )
    }

    /**
     * Decides what a touch at ([x], [y]) — in pixels relative to the drawn
     * photo of [imageWidth] x [imageHeight] — grabs: the nearest corner within
     * [touchRadius], the whole selection when inside it, or nothing.
     */
    fun dragAt(x: Float, y: Float, imageWidth: Float, imageHeight: Float, touchRadius: Float): CropDrag? {
        val corners = listOf(
            CropHandle.TOP_LEFT to (left * imageWidth to top * imageHeight),
            CropHandle.TOP_RIGHT to (right * imageWidth to top * imageHeight),
            CropHandle.BOTTOM_LEFT to (left * imageWidth to bottom * imageHeight),
            CropHandle.BOTTOM_RIGHT to (right * imageWidth to bottom * imageHeight),
        )
        val radiusSquared = touchRadius * touchRadius
        val grabbed = corners
            .map { (handle, corner) ->
                val (cornerX, cornerY) = corner
                val distanceSquared = (x - cornerX) * (x - cornerX) + (y - cornerY) * (y - cornerY)
                handle to distanceSquared
            }
            .filter { (_, distanceSquared) -> distanceSquared <= radiusSquared }
            .minByOrNull { (_, distanceSquared) -> distanceSquared }
        val inside = x >= left * imageWidth && x <= right * imageWidth &&
            y >= top * imageHeight && y <= bottom * imageHeight
        return when {
            grabbed != null -> CropDrag.Corner(grabbed.first)
            inside -> CropDrag.Move
            else -> null
        }
    }

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}
