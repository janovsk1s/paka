package com.paka.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher

/** PDFs use the wrapped data key; Keystore-only v1 files must stay readable and migrate once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfStoreEnvelopeTest {
    private lateinit var context: Context
    private val pdf = "%PDF-1.7 envelope test".toByteArray()
    private val id = documentDigest(pdf)

    @Before
    fun setUp() {
        TestKeys.install()
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
    }

    @Test
    fun newPdfsUseTheDataKeyEnvelopeAndRoundTrip() {
        PdfStore.writePlaintext(context, id, pdf.copyOf())

        assertEquals(2, encryptedFile().readBytes()[3].toInt())
        assertArrayEquals(pdf, PdfStore.readPlaintext(context, id))
    }

    @Test
    fun keystoreOnlyV1PdfStillDecryptsAndIsRewrittenAsV2() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, TestKeys.key("paka_pdf_key"))
        cipher.updateAAD("paka-pdf-v1:$id".toByteArray(Charsets.UTF_8))
        val magic = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'P'.code.toByte(), 1)
        val v1 = magic + cipher.iv + cipher.doFinal(pdf)
        encryptedFile().apply { parentFile?.mkdirs() }.writeBytes(v1)

        assertArrayEquals(pdf, PdfStore.readPlaintext(context, id))
        assertEquals(2, encryptedFile().readBytes()[3].toInt())
        assertArrayEquals(pdf, PdfStore.readPlaintext(context, id))
    }

    @Test
    fun unreadableWrappedKeyFailsLoudlyInsteadOfRegenerating() {
        val directory = File(context.filesDir, "keys").apply { mkdirs() }
        File(directory, "test.dek").writeBytes(ByteArray(64) { 7 })
        val key = WrappedDataKey("keys", "test.dek", "paka_test_key", "test-dek".toByteArray())

        assertThrows(IllegalStateException::class.java) { key.get(context) }
        assertEquals(64L, File(directory, "test.dek").length())
    }

    private fun encryptedFile() = File(File(context.filesDir, "pdfs"), "$id.pdf.enc")
}
