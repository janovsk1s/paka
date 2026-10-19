package com.paka.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every field a pass or 2FA account carries must survive export and restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupStoreRoundTripTest {
    private val passphrase = "correct horse battery".toCharArray()
    private val pdf = "%PDF-1.7 tiny".toByteArray()
    private val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
    private val reference = PassReference("content://docs/ticket", "ticket.pdf", "application/pdf")

    private val barcode = Card(
        name = "Library",
        data = "PAKA-123",
        format = PakaFormat.CODE128,
        id = "card-1",
        createdAt = 1_700_000_000_000L,
        notes = "renew in May",
        stack = "Cards",
        references = listOf(reference),
    )
    private val pdfPass = Card(
        name = "Train",
        content = PassContent.Pdf(pdfDocumentId(pdf), pageCount = 2),
        id = "card-2",
        createdAt = 1_700_000_000_001L,
    )
    private val photoPass = Card(
        name = "ID",
        content = PassContent.Photos(listOf(PhotoPage(photoDocumentId(photo), width = 300, height = 200))),
        id = "card-3",
        createdAt = 1_700_000_000_002L,
        stack = "Documents",
    )
    private val account = OtpAccount(
        issuer = "Example",
        account = "me@example.com",
        secret = "JBSWY3DPEHPK3PXP",
        digits = 8,
        period = 60,
        algorithm = "SHA256",
        id = "otp-1",
        createdAt = 1_700_000_000_003L,
    )

    @Test
    fun photoBackupRoundTripsEveryFieldExceptExternalReferences() {
        val restored = roundTrip(
            listOf(barcode, pdfPass, photoPass),
            documents = mapOf(pdfDocumentId(pdf) to pdf.copyOf()),
            photos = mapOf(photoDocumentId(photo) to photo.copyOf()),
        )

        // References are device-local links and are deliberately left out of backups.
        assertEquals(listOf(barcode.copy(references = emptyList()), pdfPass, photoPass), restored.cards)
        assertEquals(listOf(account), restored.accounts)
        assertArrayEquals(pdf, restored.documents.getValue(pdfDocumentId(pdf)))
        assertArrayEquals(photo, restored.photos.getValue(photoDocumentId(photo)))
    }

    @Test
    fun stableCompatibleSchemasRoundTripWithoutPhotos() {
        val barcodeOnly = roundTrip(listOf(barcode))
        assertEquals(listOf(barcode.copy(references = emptyList())), barcodeOnly.cards)

        val withPdf = roundTrip(listOf(pdfPass), documents = mapOf(pdfDocumentId(pdf) to pdf.copyOf()))
        assertEquals(listOf(pdfPass), withPdf.cards)
        assertTrue(withPdf.photos.isEmpty())
        assertArrayEquals(pdf, withPdf.documents.getValue(pdfDocumentId(pdf)))
    }

    private fun roundTrip(
        cards: List<Card>,
        documents: Map<String, ByteArray> = emptyMap(),
        photos: Map<String, ByteArray> = emptyMap(),
    ): BackupData {
        val encrypted = BackupStore.encrypt(cards, listOf(account), passphrase.copyOf(), documents, photos)
        return BackupStore.decrypt(encrypted, passphrase.copyOf())
    }
}
