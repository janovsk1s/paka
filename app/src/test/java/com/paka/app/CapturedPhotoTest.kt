package com.paka.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** In-app captures must come out rotated, bounded, JPEG-encoded, and ingestible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapturedPhotoTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        TestKeys.install()
        context = ApplicationProvider.getApplicationContext()
    }

    private fun jpegOf(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun twoToneJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            val column = if (x < width / 2) Color.BLACK else Color.WHITE
            for (y in 0 until height) bitmap.setPixel(x, y, column)
        }
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun quadrantJpeg(width: Int = 240, height: Int = 160): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x,
                    y,
                    when {
                        x < width / 2 && y < height / 2 -> Color.RED
                        x >= width / 2 && y < height / 2 -> Color.GREEN
                        x < width / 2 -> Color.BLUE
                        else -> Color.YELLOW
                    },
                )
            }
        }
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    /** Adds a minimal little-endian EXIF orientation IFD after the JPEG SOI. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
            orientation.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff
        val length = payload.size + 2
        val output = ByteArrayOutputStream(jpeg.size + payload.size + 4)
        output.write(jpeg, 0, 2)
        output.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length ushr 8).toByte(), length.toByte()))
        output.write(payload)
        output.write(jpeg, 2, jpeg.size - 2)
        return output.toByteArray()
    }

    private fun nearestQuadrantColor(pixel: Int): Int {
        val palette = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        return palette.minBy { expected ->
            val red = Color.red(pixel) - Color.red(expected)
            val green = Color.green(pixel) - Color.green(expected)
            val blue = Color.blue(pixel) - Color.blue(expected)
            red * red + green * green + blue * blue
        }
    }

    private fun assertCorners(bitmap: Bitmap, expected: List<Int>) {
        val actual = listOf(
            bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
            bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
            bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
            bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4),
        ).map(::nearestQuadrantColor)
        assertEquals(expected, actual)
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun sensorRotationIsBakedIntoThePixels() {
        val normalized = CapturedPhoto.normalize(jpegOf(320, 200), rotationDegrees = 90)
        assertEquals(200 to 320, dimensionsOf(normalized))
        assertTrue("output must be a JPEG", PhotoStore.hasSupportedHeader(normalized))
    }

    @Test
    fun unrotatedCapturesKeepTheirDimensions() {
        val normalized = CapturedPhoto.normalize(jpegOf(320, 200), rotationDegrees = 0)
        assertEquals(320 to 200, dimensionsOf(normalized))
    }

    @Test
    fun oversizedCapturesAreBoundedToTheMaximumEdge() {
        val normalized = CapturedPhoto.normalize(jpegOf(CapturedPhoto.MAX_DIMENSION * 2, 64), rotationDegrees = 0)
        val (width, _) = dimensionsOf(normalized)
        assertTrue("long edge must be bounded, was $width", width <= CapturedPhoto.MAX_DIMENSION)
    }

    @Test
    fun capturesAboveThePhotoDimensionCapAreRejectedBeforeFullDecode() {
        val tooWide = jpegOf(PhotoStore.MAX_DIMENSION + 1, 1)

        assertThrows(IllegalArgumentException::class.java) {
            CapturedPhoto.normalize(tooWide, rotationDegrees = 0)
        }
    }

    @Test
    fun garbageBytesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CapturedPhoto.normalize(ByteArray(64) { 0x33 }, rotationDegrees = 0)
        }
    }

    @Test
    fun croppingCutsTheSelectedRegion() {
        val source = twoToneJpeg(320, 200)
        val rightHalf = CapturedPhoto.applyEdits(source, 0, CropRect(0.5f, 0f, 1f, 1f))

        assertEquals(160 to 200, dimensionsOf(rightHalf))
        val decoded = BitmapFactory.decodeByteArray(rightHalf, 0, rightHalf.size)
        val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
        decoded.recycle()
        assertTrue("cropped half must contain the white side", Color.red(center) > 200)
    }

    @Test
    fun rotationTurnsTheWorkingPhotoCounterClockwise() {
        val rotated = CapturedPhoto.applyEdits(jpegOf(320, 200), 1, CropRect.FULL)
        assertEquals(200 to 320, dimensionsOf(rotated))
        assertTrue("output must be a JPEG", PhotoStore.hasSupportedHeader(rotated))
    }

    @Test
    fun rotationCanApplyMultipleQueuedTurnsAtOnce() {
        val upsideDown = CapturedPhoto.applyEdits(jpegOf(320, 200), 2, CropRect.FULL)
        val fullTurn = CapturedPhoto.applyEdits(jpegOf(320, 200), 4, CropRect.FULL)

        assertEquals(320 to 200, dimensionsOf(upsideDown))
        assertEquals(320 to 200, dimensionsOf(fullTurn))
        assertTrue("queued rotation output must be a JPEG", PhotoStore.hasSupportedHeader(upsideDown))
        assertTrue("full-turn output must remain a JPEG", PhotoStore.hasSupportedHeader(fullTurn))
    }

    @Test
    @Config(sdk = [26, 34])
    fun everyExifOrientationMovesPixelsToTheReviewedPositions() {
        val source = quadrantJpeg()
        val cases = listOf(
            ExifInterface.ORIENTATION_NORMAL to listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
        )
        val axisSwappingOrientations = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )

        cases.forEach { (orientation, expectedCorners) ->
            val reviewed = CapturedPhoto.decodePreview(withExifOrientation(source, orientation), 1_440)
            if (orientation in axisSwappingOrientations) {
                assertEquals(160 to 240, reviewed.width to reviewed.height)
            } else {
                assertEquals(240 to 160, reviewed.width to reviewed.height)
            }
            assertCorners(reviewed, expectedCorners)
            reviewed.recycle()
        }
    }

    @Test
    fun exifUserRotationAndCropShareOneCorrectPixelTransform() {
        val source = withExifOrientation(quadrantJpeg(), ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        val cropped = CapturedPhoto.applyEdits(
            bytes = source,
            counterClockwiseTurns = 1,
            selection = CropRect(0f, 0f, 1f, 0.5f),
        )
        val decoded = BitmapFactory.decodeByteArray(cropped, 0, cropped.size)

        assertEquals(160 to 120, decoded.width to decoded.height)
        assertCorners(decoded, listOf(Color.RED, Color.BLUE, Color.RED, Color.BLUE))
        decoded.recycle()
    }

    @Test
    @Config(sdk = [26, 34])
    fun untouchedExifImportKeepsOriginalBytesAndStoresOrientedDimensions() {
        val original = withExifOrientation(quadrantJpeg(), ExifInterface.ORIENTATION_ROTATE_90)
        val imported = PhotoStore.importBytes(context, original).getOrThrow()

        assertEquals(160 to 240, imported.page.width to imported.page.height)
        assertTrue(PhotoStore.readPlaintext(context, imported.page.documentId).contentEquals(original))
    }

    @Test
    fun normalizedCapturesRoundTripThroughTheEncryptedStore() {
        val normalized = CapturedPhoto.normalize(jpegOf(320, 200), rotationDegrees = 90)
        val imported = PhotoStore.importBytes(context, normalized).getOrThrow()

        assertTrue(imported.created)
        assertEquals(200, imported.page.width)
        assertEquals(320, imported.page.height)
        assertTrue(PhotoStore.readPlaintext(context, imported.page.documentId).contentEquals(normalized))

        val again = PhotoStore.importBytes(context, normalized).getOrThrow()
        assertFalse("identical bytes must deduplicate", again.created)
        assertEquals(imported.page.documentId, again.page.documentId)
    }

    @Test
    fun reimportRepairsThePrimaryWithoutReplacingAKnownGoodBackup() {
        val original = jpegOf(320, 200)
        val imported = PhotoStore.importBytes(context, original).getOrThrow()
        val encrypted = File(
            File(context.filesDir, "photos"),
            "${imported.page.documentId}.image.enc",
        )
        val backup = AtomicStore.backupFile(encrypted)
        val knownGoodBackup = encrypted.readBytes()
        backup.writeBytes(knownGoodBackup)
        encrypted.writeBytes("corrupt primary".toByteArray())

        val repaired = PhotoStore.importBytes(context, original).getOrThrow()

        assertFalse("same content identifier must remain deduplicated", repaired.created)
        assertTrue(backup.readBytes().contentEquals(knownGoodBackup))
        assertTrue(PhotoStore.readPlaintext(context, repaired.page.documentId).contentEquals(original))
    }
}
