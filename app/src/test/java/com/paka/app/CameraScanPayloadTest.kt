package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraScanPayloadTest {
    private val binary = byteArrayOf(0, 1, 127, 128.toByte(), 255.toByte())
    private val exactBinary = binary.toString(Charsets.ISO_8859_1)

    @Test
    fun aztecAndPdf417PreferExactBytesOverDecodedText() {
        listOf(PakaFormat.AZTEC, PakaFormat.PDF417).forEach { format ->
            assertEquals(exactBinary, selectScanPayload(format, "lossy decoded text", binary))
        }
    }

    @Test
    fun textFormatsPreferDecoderTextAndAllFormatsRetainByteFallback() {
        assertEquals("decoder text", selectScanPayload(PakaFormat.QR, "decoder text", binary))
        assertEquals(exactBinary, selectScanPayload(PakaFormat.QR, null, binary))
    }
}
