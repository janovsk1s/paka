package com.paka.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
    fun garbageBytesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CapturedPhoto.normalize(ByteArray(64) { 0x33 }, rotationDegrees = 0)
        }
    }

    @Test
    fun croppingCutsTheSelectedRegion() {
        val source = twoToneJpeg(320, 200)
        val rightHalf = CapturedPhoto.crop(source, CropRect(0.5f, 0f, 1f, 1f))

        assertEquals(160 to 200, dimensionsOf(rightHalf))
        val decoded = BitmapFactory.decodeByteArray(rightHalf, 0, rightHalf.size)
        val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
        decoded.recycle()
        assertTrue("cropped half must contain the white side", Color.red(center) > 200)
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
}
