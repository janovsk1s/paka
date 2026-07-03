package com.paka.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Restore must replace everything atomically or leave the old data untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreWriteCoordinatorRestoreTest {

    private lateinit var context: Context

    private val oldPdf = pdfBytes("old paka document")
    private val oldPdfId = pdfDocumentId(oldPdf)
    private val oldCards = listOf(
        Card(name = "Bahnticket", content = PassContent.Pdf(oldPdfId, 1), id = "card-old-pdf"),
        Card(name = "Billa", data = "9120012345678", format = PakaFormat.EAN13, id = "card-old-barcode"),
    )
    private val oldCodes = listOf(
        OtpAccount(issuer = "GitHub", account = "old", secret = "JBSWY3DPEHPK3PXP", id = "code-old"),
    )

    @Before
    fun setUp() {
        TestKeys.install()
        context = ApplicationProvider.getApplicationContext()
        PdfStore.writePlaintext(context, oldPdfId, oldPdf)
        assertTrue(CardStore.save(context, oldCards).isSuccess)
        assertTrue(SecureStore.saveAccounts(context, oldCodes).isSuccess)
    }

    private fun pdfBytes(seed: String): ByteArray = "%PDF-1.4\n%$seed\n%%EOF\n".toByteArray(Charsets.UTF_8)

    @Test
    fun successfulRestoreReplacesStoresAndRemovesOrphans() {
        val newPdf = pdfBytes("new paka document")
        val newPdfId = pdfDocumentId(newPdf)
        val restored = BackupData(
            cards = listOf(Card(name = "Flugticket", content = PassContent.Pdf(newPdfId, 1), id = "card-new-pdf")),
            accounts = listOf(OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP", id = "code-new")),
            documents = mapOf(newPdfId to newPdf),
        )

        val outcome = runBlocking { StoreWriteCoordinator.restore(context, oldCards, oldCodes, restored).await() }

        assertEquals(RestoreOutcome.SUCCESS, outcome)
        assertEquals(restored.cards, CardStore.load(context).value)
        assertEquals(restored.accounts.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(PdfStore.readPlaintext(context, newPdfId).contentEquals(newPdf))
        assertTrue(
            "the replaced document must be deleted as an orphan",
            runCatching { PdfStore.readPlaintext(context, oldPdfId) }.isFailure,
        )
    }

    @Test
    fun invalidRestoredDocumentRollsEverythingBack() {
        val newPdf = pdfBytes("new paka document")
        // A syntactically valid but wrong identifier: PdfStore refuses to
        // persist it, which must abort the restore before any store changes.
        val mismatchedId = "0".repeat(64)
        val restored = BackupData(
            cards = listOf(Card(name = "Flugticket", content = PassContent.Pdf(mismatchedId, 1), id = "card-new-pdf")),
            accounts = listOf(OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP", id = "code-new")),
            documents = mapOf(mismatchedId to newPdf),
        )

        val outcome = runBlocking { StoreWriteCoordinator.restore(context, oldCards, oldCodes, restored).await() }

        assertEquals(RestoreOutcome.FAILED_ROLLED_BACK, outcome)
        assertEquals(oldCards, CardStore.load(context).value)
        assertEquals(oldCodes.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(
            "the original document must survive the failed restore",
            PdfStore.readPlaintext(context, oldPdfId).contentEquals(oldPdf),
        )
    }
}
