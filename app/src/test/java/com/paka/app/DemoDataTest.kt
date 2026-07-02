package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {
    @Test
    fun `demo content is valid and contains no real account domains`() {
        val demo = DemoData.create()

        assertTrue(demo.cards.size > 5)
        assertTrue(demo.accounts.size > 5)
        demo.cards.forEach {
            val barcode = requireNotNull(it.barcodeContent)
            assertNull(Barcodes.validationError(barcode.format, barcode.data))
        }
        demo.accounts.forEach {
            assertNull(Totp.validationError(it))
            assertTrue(it.account.endsWith("@example.invalid"))
        }
        assertEquals(demo.cards.size, demo.cards.map(Card::id).distinct().size)
        assertEquals(demo.accounts.size, demo.accounts.map(OtpAccount::id).distinct().size)
    }

    @Test
    fun `each demo session gets fresh secrets and pass payloads`() {
        val first = DemoData.create()
        val second = DemoData.create()

        assertNotEquals(
            first.cards.map { requireNotNull(it.barcodeContent).data },
            second.cards.map { requireNotNull(it.barcodeContent).data },
        )
        assertNotEquals(first.accounts.map(OtpAccount::secret), second.accounts.map(OtpAccount::secret))
    }
}
