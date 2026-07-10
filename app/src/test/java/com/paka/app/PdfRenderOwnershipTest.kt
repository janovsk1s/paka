package com.paka.app

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfRenderOwnershipTest {
    @Test
    fun cancelledRenderRecyclesResultBeforeItCanReachCompose() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val render = launch {
            loadOwnedPdfBitmap {
                started.complete(Unit)
                finish.await()
                bitmap
            }
        }

        started.await()
        render.cancel()
        finish.complete(Unit)
        render.join()

        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun acceptedRenderTransfersBitmapWithoutRecyclingIt() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        val accepted = loadOwnedPdfBitmap { bitmap }

        assertSame(bitmap, accepted)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }
}
