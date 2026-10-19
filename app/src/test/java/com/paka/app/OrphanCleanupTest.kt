package com.paka.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Startup cleanup shares its folders with the data keys; deleting a key would orphan every document. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrphanCleanupTest {
    private lateinit var context: Context
    private val kept = "a".repeat(64)
    private val orphan = "b".repeat(64)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
    }

    @Test
    fun pdfCleanupDeletesOnlyUnreferencedDocuments() {
        val directory = files(
            "pdfs",
            "$kept.pdf.enc",
            "$orphan.pdf.enc",
            "$orphan.pdf.enc.bak",
            "pdf.dek",
            "pdf.dek.bak",
        )

        PdfStore.deleteOrphans(context, setOf(kept))

        assertEquals(setOf("$kept.pdf.enc", "pdf.dek", "pdf.dek.bak"), directory.list().orEmpty().toSet())
    }

    @Test
    fun photoCleanupDeletesOnlyUnreferencedPhotos() {
        val directory = files(
            "photos",
            "$kept.image.enc",
            "$kept.display.enc",
            "$orphan.image.enc",
            "$orphan.display.enc",
            "photo.dek",
            "photo.dek.bak",
        )

        PhotoStore.deleteOrphans(context, setOf(kept))

        assertEquals(
            setOf("$kept.image.enc", "$kept.display.enc", "photo.dek", "photo.dek.bak"),
            directory.list().orEmpty().toSet(),
        )
    }

    private fun files(directory: String, vararg names: String) = File(context.filesDir, directory).apply {
        mkdirs()
        names.forEach { File(this, it).writeBytes(byteArrayOf(1)) }
    }
}
