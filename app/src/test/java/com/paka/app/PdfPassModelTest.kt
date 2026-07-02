package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfPassModelTest {
    @Test
    fun `PDF content is distinct from barcode content`() {
        val bytes = "%PDF-1.7\nsynthetic".toByteArray()
        val documentId = pdfDocumentId(bytes)
        val pdf = Card("Document", PassContent.Pdf(documentId, 2))
        val barcode = Card("Code", "synthetic", PakaFormat.QR)

        assertEquals(documentId, pdf.pdfContent?.documentId)
        assertEquals(2, pdf.pdfContent?.pageCount)
        assertNull(pdf.barcodeContent)
        assertEquals("synthetic", barcode.barcodeContent?.data)
        assertNull(barcode.pdfContent)
    }

    @Test
    fun `document identifiers are stable SHA-256 values`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            pdfDocumentId("abc".toByteArray()),
        )
    }
}
