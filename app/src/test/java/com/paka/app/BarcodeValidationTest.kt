package com.paka.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeValidationTest {
    @Test
    fun `retail formats enforce digit count and character set`() {
        assertNull(Barcodes.validationError(PakaFormat.EAN13, "590123412345"))
        assertNotNull(Barcodes.validationError(PakaFormat.EAN13, "59012341234"))
        assertNotNull(Barcodes.validationError(PakaFormat.EAN13, "59012341234X"))
        assertNull(Barcodes.validationError(PakaFormat.UPCA, "04210000526"))
        assertNotNull(Barcodes.validationError(PakaFormat.UPCA, "0421000052"))
    }

    @Test
    fun `linear formats reject unsupported payloads`() {
        assertNull(Barcodes.validationError(PakaFormat.ITF, "12345678"))
        assertNotNull(Barcodes.validationError(PakaFormat.ITF, "1234567"))
        assertNotNull(Barcodes.validationError(PakaFormat.ITF, "1234AB"))
        assertNull(Barcodes.validationError(PakaFormat.CODE39, "PAKA-123"))
        assertNotNull(Barcodes.validationError(PakaFormat.CODE39, "paka-123"))
        assertNull(Barcodes.validationError(PakaFormat.CODABAR, "A12345B"))
        assertNotNull(Barcodes.validationError(PakaFormat.CODABAR, "12345"))
    }
}
