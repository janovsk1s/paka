package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPassModelTest {
    @Test
    fun `photo content stores one or two ordered sides`() {
        val front = PhotoPage("a".repeat(64), 1200, 800)
        val back = PhotoPage("b".repeat(64), 1200, 800)
        val card = Card("ID", PassContent.Photos(listOf(front, back)))

        assertEquals(listOf(front, back), card.photoContent?.pages)
        assertNull(card.barcodeContent)
        assertNull(card.pdfContent)
        assertEquals(setOf(front.documentId, back.documentId), listOf(card).photoDocumentIds())
    }

    @Test
    fun `photo pass rejects empty and oversized side lists`() {
        assertThrows(IllegalArgumentException::class.java) { PassContent.Photos(emptyList()) }
        val page = PhotoPage("a".repeat(64), 100, 100)
        assertThrows(IllegalArgumentException::class.java) { PassContent.Photos(listOf(page, page, page)) }
    }

    @Test
    fun `photo identifiers are stable SHA-256 values`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            photoDocumentId("abc".toByteArray()),
        )
    }

    @Test
    fun `portable backup image headers are narrowly accepted`() {
        assertTrue(PhotoStore.hasSupportedHeader(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)))
        assertTrue(PhotoStore.hasSupportedHeader(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        assertTrue(PhotoStore.hasSupportedHeader("RIFF0000WEBP".toByteArray()))
    }
}
