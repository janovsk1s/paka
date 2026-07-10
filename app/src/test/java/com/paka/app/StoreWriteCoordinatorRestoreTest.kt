package com.paka.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        PdfStore.writePlaintext(context, oldPdfId, oldPdf)
        assertTrue(CardStore.save(context, oldCards).isSuccess)
        assertTrue(SecureStore.saveAccounts(context, oldCodes).isSuccess)
    }

    private fun pdfBytes(seed: String): ByteArray = "%PDF-1.4\n%$seed\n%%EOF\n".toByteArray(Charsets.UTF_8)

    private fun blockCardSnapshotCleanup(): File {
        val snapshot = File(context.filesDir, "restore.cards.enc")
        return AtomicStore.corruptFile(snapshot).also { blocker ->
            assertTrue(blocker.mkdirs())
            File(blocker, "keep").writeText("cleanup must retry")
        }
    }

    private fun restoreJournalExists(): Boolean {
        val journal = File(context.filesDir, "restore.journal")
        return journal.exists() || AtomicStore.backupFile(journal).exists()
    }

    private fun corruptRestoreJournalPrimary() {
        File(context.filesDir, "restore.journal").writeText("corrupt terminal marker")
    }

    @Test
    fun successfulRestoreReplacesStoresAndRemovesOrphans() {
        val newPdf = pdfBytes("new paka document")
        val expectedNewPdf = newPdf.copyOf()
        val newPdfId = pdfDocumentId(newPdf)
        val restored = BackupData(
            cards = listOf(Card(name = "Flugticket", content = PassContent.Pdf(newPdfId, 1), id = "card-new-pdf")),
            accounts = listOf(OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP", id = "code-new")),
            documents = mapOf(newPdfId to newPdf),
        )

        val outcome = runBlocking { StoreWriteCoordinator.restore(context, restored).await() }

        assertEquals(RestoreOutcome.SUCCESS, outcome)
        assertEquals(restored.cards, CardStore.load(context).value)
        assertEquals(restored.accounts.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(PdfStore.readPlaintext(context, newPdfId).contentEquals(expectedNewPdf))
        assertTrue("the coordinator must clear owned restore plaintext", newPdf.all { it == 0.toByte() })
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

        val outcome = runBlocking { StoreWriteCoordinator.restore(context, restored).await() }

        assertEquals(RestoreOutcome.FAILED_ROLLED_BACK, outcome)
        assertEquals(oldCards, CardStore.load(context).value)
        assertEquals(oldCodes.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(
            "the original document must survive the failed restore",
            PdfStore.readPlaintext(context, oldPdfId).contentEquals(oldPdf),
        )
    }

    @Test
    fun abandonedImportCleanupWaitsForEarlierCardSaveBeforeCheckingReferences() {
        val importedPdf = pdfBytes("queued card document")
        val importedPdfId = pdfDocumentId(importedPdf)
        val importedCard = Card(
            name = "Queued ticket",
            content = PassContent.Pdf(importedPdfId, 1),
            id = "card-queued-pdf",
        )
        PdfStore.writePlaintext(context, importedPdfId, importedPdf)
        val save = checkNotNull(StoreWriteCoordinator.saveCards(context, oldCards + importedCard))
        val cleanup = checkNotNull(
            StoreWriteCoordinator.deleteUnreferencedImports(context, pdfIds = setOf(importedPdfId)),
        )

        runBlocking {
            cleanup.await()
            assertEquals(StoreWriteStatus.SAVED, save.await())
        }

        assertTrue(
            "cleanup must retain a document referenced by an earlier queued save",
            PdfStore.readPlaintext(context, importedPdfId).contentEquals(importedPdf),
        )
    }

    @Test
    fun restoreRepairsAReferencedDocumentWhoseEncryptedPrimaryIsCorrupt() {
        val encrypted = File(File(context.filesDir, "pdfs"), "$oldPdfId.pdf.enc")
        val knownGoodEncrypted = encrypted.readBytes()
        AtomicStore.backupFile(encrypted).writeBytes(knownGoodEncrypted)
        encrypted.writeBytes("corrupt primary".toByteArray())
        val expectedPdf = oldPdf.copyOf()
        val restored = BackupData(
            cards = oldCards,
            accounts = oldCodes,
            documents = mapOf(oldPdfId to oldPdf),
        )

        val outcome = runBlocking { StoreWriteCoordinator.restore(context, restored).await() }

        assertEquals(RestoreOutcome.SUCCESS, outcome)
        assertTrue(PdfStore.readPlaintext(context, oldPdfId).contentEquals(expectedPdf))
        assertTrue(
            "verified replacement must not rotate a corrupt primary over the known-good backup",
            AtomicStore.backupFile(encrypted).readBytes().contentEquals(knownGoodEncrypted),
        )
    }

    @Test
    @Config(sdk = [29])
    fun pdfRestoreOnAndroid10FailsBeforeChangingStores() {
        val expectedPdf = oldPdf.copyOf()
        val restored = BackupData(
            cards = oldCards,
            accounts = listOf(
                OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP"),
            ),
            documents = mapOf(oldPdfId to oldPdf),
        )

        val outcome = runBlocking { StoreWriteCoordinator.restore(context, restored).await() }

        assertEquals(RestoreOutcome.FAILED_ROLLED_BACK, outcome)
        assertEquals(oldCards, CardStore.load(context).value)
        assertEquals(oldCodes.map(OtpAccount::id), SecureStore.loadAccounts(context).value.map(OtpAccount::id))
        assertTrue(PdfStore.readPlaintext(context, oldPdfId).contentEquals(expectedPdf))
    }

    @Test
    fun preparedJournalRollsBackCrashAfterCodesWereReplaced() {
        val newPdf = pdfBytes("new paka document")
        val newPdfId = pdfDocumentId(newPdf)
        val newCards = listOf(Card(name = "Flugticket", content = PassContent.Pdf(newPdfId, 1), id = "card-new-pdf"))
        val newCodes = listOf(
            OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP", id = "code-new"),
        )
        val prepared = RestoreJournal.begin(context, oldCards, oldCodes.size, newCards).getOrThrow()
        assertEquals(RestoreJournalPhase.PREPARED, prepared.phase)
        PdfStore.writePlaintext(context, newPdfId, newPdf)
        assertTrue(SecureStore.saveAccounts(context, newCodes).isSuccess)
        // Simulate process death before cards and the COMMITTED marker.

        val recovery = RestoreJournal.recover(context)

        assertEquals(RestoreRecoveryOutcome.ROLLED_BACK, recovery)
        assertEquals(oldCards, CardStore.load(context).value)
        assertEquals(oldCodes.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(PdfStore.readPlaintext(context, oldPdfId).contentEquals(oldPdf))
        assertTrue(runCatching { PdfStore.readPlaintext(context, newPdfId) }.isFailure)
    }

    @Test
    fun rolledBackJournalRetainsMarkerUntilSnapshotCleanupCanRetry() {
        val newPdf = pdfBytes("new paka document")
        val newPdfId = pdfDocumentId(newPdf)
        val newCards = listOf(
            Card(name = "Flugticket", content = PassContent.Pdf(newPdfId, 1), id = "card-new-pdf"),
        )
        val newCodes = listOf(
            OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP", id = "code-new"),
        )
        RestoreJournal.begin(context, oldCards, oldCodes.size, newCards).getOrThrow()
        PdfStore.writePlaintext(context, newPdfId, newPdf)
        assertTrue(CardStore.save(context, newCards).isSuccess)
        assertTrue(SecureStore.saveAccounts(context, newCodes).isSuccess)
        val cleanupBlocker = blockCardSnapshotCleanup()

        val firstRecovery = RestoreJournal.recover(context)

        assertEquals(RestoreRecoveryOutcome.FAILED, firstRecovery)
        assertEquals(oldCards, CardStore.load(context).value)
        assertEquals(oldCodes.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(restoreJournalExists())
        assertFalse(File(context.filesDir, "restore.cards.enc").exists())
        assertTrue(cleanupBlocker.deleteRecursively())
        corruptRestoreJournalPrimary()

        val retry = RestoreJournal.recover(context)

        assertEquals(RestoreRecoveryOutcome.ROLLED_BACK, retry)
        assertFalse(restoreJournalExists())
        assertEquals(oldCards, CardStore.load(context).value)
    }

    @Test
    fun committedJournalFinishesCrashCleanup() {
        val newPdf = pdfBytes("new paka document")
        val newPdfId = pdfDocumentId(newPdf)
        val newCards = listOf(Card(name = "Flugticket", content = PassContent.Pdf(newPdfId, 1), id = "card-new-pdf"))
        val newCodes = listOf(
            OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP", id = "code-new"),
        )
        val prepared = RestoreJournal.begin(context, oldCards, oldCodes.size, newCards).getOrThrow()
        PdfStore.writePlaintext(context, newPdfId, newPdf)
        assertTrue(SecureStore.saveAccounts(context, newCodes).isSuccess)
        assertTrue(CardStore.save(context, newCards).isSuccess)
        RestoreJournal.markCommitted(context, prepared).getOrThrow()
        // Simulate process death before old-document cleanup.

        val recovery = RestoreJournal.recover(context)

        assertEquals(RestoreRecoveryOutcome.COMMITTED, recovery)
        assertEquals(newCards, CardStore.load(context).value)
        assertEquals(newCodes.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(PdfStore.readPlaintext(context, newPdfId).contentEquals(newPdf))
        assertTrue(runCatching { PdfStore.readPlaintext(context, oldPdfId) }.isFailure)
    }

    @Test
    fun committedJournalRetainsMarkerUntilSnapshotCleanupCanRetry() {
        val newPdf = pdfBytes("new paka document")
        val newPdfId = pdfDocumentId(newPdf)
        val newCards = listOf(
            Card(name = "Flugticket", content = PassContent.Pdf(newPdfId, 1), id = "card-new-pdf"),
        )
        val newCodes = listOf(
            OtpAccount(issuer = "Mastodon", account = "new", secret = "JBSWY3DPEHPK3PXP", id = "code-new"),
        )
        val prepared = RestoreJournal.begin(context, oldCards, oldCodes.size, newCards).getOrThrow()
        PdfStore.writePlaintext(context, newPdfId, newPdf)
        assertTrue(CardStore.save(context, newCards).isSuccess)
        assertTrue(SecureStore.saveAccounts(context, newCodes).isSuccess)
        RestoreJournal.markCommitted(context, prepared).getOrThrow()
        val cleanupBlocker = blockCardSnapshotCleanup()

        val firstRecovery = RestoreJournal.recover(context)

        assertEquals(RestoreRecoveryOutcome.FAILED, firstRecovery)
        assertEquals(newCards, CardStore.load(context).value)
        assertEquals(newCodes.map { it.id }, SecureStore.loadAccounts(context).value.map { it.id })
        assertTrue(restoreJournalExists())
        assertTrue(cleanupBlocker.deleteRecursively())
        corruptRestoreJournalPrimary()

        val retry = RestoreJournal.recover(context)

        assertEquals(RestoreRecoveryOutcome.COMMITTED, retry)
        assertFalse(restoreJournalExists())
        assertEquals(newCards, CardStore.load(context).value)
    }

    @Test
    fun rollbackToInitiallyMissingStoresRemovesRestoredGenerations() {
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        val newCards = listOf(Card(name = "Billa", data = "9120012345678", format = PakaFormat.EAN13))
        val newCodes = listOf(OtpAccount(issuer = "GitHub", account = "new", secret = "JBSWY3DPEHPK3PXP"))
        RestoreJournal.begin(context, emptyList(), 0, newCards).getOrThrow()
        assertTrue(CardStore.save(context, newCards).isSuccess)
        assertTrue(SecureStore.saveAccounts(context, newCodes).isSuccess)

        val recovery = RestoreJournal.recover(context)

        assertEquals(RestoreRecoveryOutcome.ROLLED_BACK, recovery)
        assertFalse(CardStore.storageFile(context).exists())
        assertFalse(AtomicStore.backupFile(CardStore.storageFile(context)).exists())
        assertFalse(SecureStore.storageFile(context).exists())
        assertFalse(AtomicStore.backupFile(SecureStore.storageFile(context)).exists())
        assertEquals(emptyList<Card>(), CardStore.load(context).value)
        assertEquals(emptyList<OtpAccount>(), SecureStore.loadAccounts(context).value)
    }
}
