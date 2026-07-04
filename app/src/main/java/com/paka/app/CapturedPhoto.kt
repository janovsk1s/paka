package com.paka.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Normalizes an in-app camera capture entirely in memory: bakes the sensor
 * rotation into the pixels, bounds the longest edge, and re-encodes a fresh
 * JPEG so no camera metadata (EXIF timestamps, device identifiers) survives.
 * Every intermediate buffer is zeroed, including on failure.
 */
internal object CapturedPhoto {
    const val MAX_DIMENSION = 3072
    private const val JPEG_QUALITY = 90

    // Exposes the inherited buffer so compressed plaintext can be zeroed
    // after the caller takes its copy.
    private class ZeroableOutputStream : ByteArrayOutputStream() {
        fun zero() = buf.fill(0)
    }

    fun normalize(jpeg: ByteArray, rotationDegrees: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The captured photo could not be decoded" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("The captured photo could not be decoded")
        val scale = (MAX_DIMENSION.toFloat() / max(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees.toFloat())
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return encode(transformed)
    }

    /** Cuts the selection out of an already-normalized capture, in memory. */
    fun crop(jpeg: ByteArray, selection: CropRect): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("The captured photo could not be decoded")
        val x = (selection.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (selection.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val width = (selection.width * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
        val height = (selection.height * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
        val cut = Bitmap.createBitmap(bitmap, x, y, width, height)
        if (cut !== bitmap) bitmap.recycle()
        return encode(cut)
    }

    /** Encodes and recycles [bitmap], zeroing the compression buffer. */
    private fun encode(bitmap: Bitmap): ByteArray = try {
        val output = ZeroableOutputStream()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "The captured photo could not be encoded"
            }
            output.toByteArray()
        } finally {
            output.zero()
        }
    } finally {
        bitmap.recycle()
    }
}
