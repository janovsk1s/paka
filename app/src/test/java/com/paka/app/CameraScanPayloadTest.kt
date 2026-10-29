package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import zxingcpp.BarcodeReader.ContentType

class CameraScanPayloadTest {
    private val binary = byteArrayOf(0, 1, 127, 128.toByte(), 255.toByte())
    private val exactBinary = binary.toString(Charsets.ISO_8859_1)

    @Test
    fun aztecAndPdf417PreferExactBytesOverDecodedText() {
        listOf(PakaFormat.AZTEC, PakaFormat.PDF417).forEach { format ->
            listOf(ContentType.TEXT, ContentType.BINARY, ContentType.MIXED).forEach { type ->
                assertEquals(exactBinary, selectScanPayload(format, type, "lossy decoded text", binary))
            }
        }
    }

    @Test
    fun textFormatsUsePlainDecoderText() {
        assertEquals("decoder text", selectScanPayload(PakaFormat.QR, ContentType.TEXT, "decoder text", binary))
        assertEquals("Rīga", selectScanPayload(PakaFormat.DATA_MATRIX, ContentType.TEXT, "Rīga", null))
    }

    @Test
    fun alteredHriTextIsRefused() {
        // HRI escapes control bytes ("<GS>") and brackets GS1 AIs; neither is the real payload.
        assertNull(selectScanPayload(PakaFormat.QR, ContentType.MIXED, "ID<GS>12345", binary))
        assertNull(selectScanPayload(PakaFormat.DATA_MATRIX, ContentType.BINARY, "<NUL>", binary))
        assertNull(selectScanPayload(PakaFormat.DATA_MATRIX, ContentType.ISO15434, "[)><RS>06", binary))
        assertNull(selectScanPayload(PakaFormat.QR, ContentType.UNKNOWN_ECI, "text", binary))
        assertNull(selectScanPayload(PakaFormat.CODE128, ContentType.GS1, "(01)09501101530003", null))
        assertNull(selectScanPayload(PakaFormat.AZTEC, ContentType.GS1, "(01)09501101530003", binary))
        assertNull(selectScanPayload(PakaFormat.QR, ContentType.TEXT, null, binary))
    }

    @Test
    fun dataBarExpandedKeepsGs1TextForCanonicalVerification() {
        assertEquals(
            "(01)09501101530003(3103)000123",
            selectScanPayload(PakaFormat.DATABAR_EXPANDED, ContentType.GS1, "(01)09501101530003(3103)000123", null),
        )
    }
}
