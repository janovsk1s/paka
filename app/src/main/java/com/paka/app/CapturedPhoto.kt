package com.paka.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Bounded, in-memory preparation for captured and selected document photos.
 * BitmapFactory deliberately leaves EXIF orientation unapplied, allowing the
 * complete mirrored/rotated transform and the user's rotation/crop to be drawn
 * into one output bitmap before one JPEG encode.
 */
internal object CapturedPhoto {
    const val MAX_DIMENSION = 3072
    private const val MAX_SOURCE_PIXELS = 48_000_000L
    private const val JPEG_QUALITY = 90
    private const val QUARTER_TURN_DEGREES = 90
    private const val FULL_TURN_QUARTERS = 4

    private data class PixelTransform(
        val matrix: Matrix,
        val width: Int,
        val height: Int,
    )

    // Exposes the inherited buffer so compressed plaintext can be zeroed
    // after the caller takes its copy.
    private class ZeroableOutputStream : ByteArrayOutputStream() {
        fun zero() = buf.fill(0)
    }

    /** Bakes CameraX's clockwise sensor correction into a fresh bounded JPEG. */
    fun normalize(jpeg: ByteArray, rotationDegrees: Int): ByteArray {
        require(rotationDegrees % QUARTER_TURN_DEGREES == 0) {
            "Capture rotation must be a multiple of 90 degrees"
        }
        val clockwiseTurns = (rotationDegrees / QUARTER_TURN_DEGREES).floorMod(FULL_TURN_QUARTERS)
        return transform(
            bytes = jpeg,
            exifOrientation = ExifInterface.ORIENTATION_NORMAL,
            counterClockwiseTurns = -clockwiseTurns,
            selection = CropRect.FULL,
        )
    }

    /**
     * Applies EXIF (including mirrored orientations), queued quarter turns and
     * the crop in one bounded decode/draw/encode operation.
     */
    fun applyEdits(bytes: ByteArray, counterClockwiseTurns: Int, selection: CropRect): ByteArray =
        transform(
            bytes = bytes,
            exifOrientation = exifOrientation(bytes),
            counterClockwiseTurns = counterClockwiseTurns,
            selection = selection,
        )

    /** Screen-sized, EXIF-correct preview. The caller owns and recycles it. */
    fun decodePreview(
        bytes: ByteArray,
        maxDimension: Int,
        counterClockwiseTurns: Int = 0,
    ): Bitmap {
        require(maxDimension > 0)
        val raw = decodeBounded(bytes, maxDimension)
        val sourceTransform = orientationTransform(exifOrientation(bytes), raw.width, raw.height)
        val turnTransform = quarterTurnTransform(
            counterClockwiseTurns,
            sourceTransform.width,
            sourceTransform.height,
        )
        val combined = Matrix().apply { setConcat(turnTransform.matrix, sourceTransform.matrix) }
        return render(raw, combined, turnTransform.width, turnTransform.height, CropRect.FULL)
    }

    /** Dimensions after EXIF is applied, without decoding a full bitmap. */
    fun orientedDimensions(bytes: ByteArray, rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val transform = orientationTransform(exifOrientation(bytes), rawWidth, rawHeight)
        return transform.width to transform.height
    }

    private fun transform(
        bytes: ByteArray,
        exifOrientation: Int,
        counterClockwiseTurns: Int,
        selection: CropRect,
    ): ByteArray {
        val raw = decodeBounded(bytes, MAX_DIMENSION)
        val sourceTransform = orientationTransform(exifOrientation, raw.width, raw.height)
        val turnTransform = quarterTurnTransform(
            counterClockwiseTurns,
            sourceTransform.width,
            sourceTransform.height,
        )
        val combined = Matrix().apply { setConcat(turnTransform.matrix, sourceTransform.matrix) }
        val transformed = render(raw, combined, turnTransform.width, turnTransform.height, selection)
        return encode(transformed)
    }

    private fun decodeBounded(bytes: ByteArray, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The captured photo could not be decoded" }
        require(bounds.outWidth <= PhotoStore.MAX_DIMENSION && bounds.outHeight <= PhotoStore.MAX_DIMENSION) {
            "Photo dimensions are unsupported"
        }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) { "Photo has too many pixels" }
        var sample = 1
        // A power-of-two sample that never leaves a 48 MP source fully decoded.
        // Undershooting the requested edge is preferable to a transient 100+ MB
        // ARGB allocation on a memory-constrained phone.
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("The captured photo could not be decoded")
    }

    private fun render(
        source: Bitmap,
        matrix: Matrix,
        transformedWidth: Int,
        transformedHeight: Int,
        selection: CropRect,
    ): Bitmap {
        if (matrix.isIdentity && selection == CropRect.FULL) return source
        val left = (selection.left * transformedWidth).toInt().coerceIn(0, transformedWidth - 1)
        val top = (selection.top * transformedHeight).toInt().coerceIn(0, transformedHeight - 1)
        val right = (selection.right * transformedWidth).toInt().coerceIn(left + 1, transformedWidth)
        val bottom = (selection.bottom * transformedHeight).toInt().coerceIn(top + 1, transformedHeight)
        var output: Bitmap? = null
        var completed = false
        return try {
            val target = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
            output = target
            val cropTranslation = Matrix().apply { setTranslate(-left.toFloat(), -top.toFloat()) }
            val drawMatrix = Matrix().apply { setConcat(cropTranslation, matrix) }
            Canvas(target).drawBitmap(
                source,
                drawMatrix,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            completed = true
            target
        } finally {
            if (!completed) output?.recycle()
            source.recycle()
        }
    }

    private fun orientationTransform(orientation: Int, width: Int, height: Int): PixelTransform {
        val matrix = Matrix()
        val swapsAxes = orientation in setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                floatArrayOf(-1f, 0f, width.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                floatArrayOf(-1f, 0f, width.toFloat(), 0f, -1f, height.toFloat(), 0f, 0f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                floatArrayOf(1f, 0f, 0f, 0f, -1f, height.toFloat(), 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE ->
                floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 ->
                floatArrayOf(0f, -1f, height.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE ->
                floatArrayOf(0f, -1f, height.toFloat(), -1f, 0f, width.toFloat(), 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 ->
                floatArrayOf(0f, 1f, 0f, -1f, 0f, width.toFloat(), 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        matrix.setValues(values)
        return PixelTransform(matrix, if (swapsAxes) height else width, if (swapsAxes) width else height)
    }

    private fun quarterTurnTransform(counterClockwiseTurns: Int, width: Int, height: Int): PixelTransform {
        val orientation = when (counterClockwiseTurns.floorMod(FULL_TURN_QUARTERS)) {
            1 -> ExifInterface.ORIENTATION_ROTATE_270
            2 -> ExifInterface.ORIENTATION_ROTATE_180
            FULL_TURN_QUARTERS - 1 -> ExifInterface.ORIENTATION_ROTATE_90
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        return orientationTransform(orientation, width, height)
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

private fun exifOrientation(bytes: ByteArray): Int = runCatching {
    ByteArrayInputStream(bytes).use { input ->
        ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
