package com.paka.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCardOwnershipTest {
    private val submitted = Card(name = "Ticket", data = "PAKA", format = PakaFormat.QR)

    @Test
    fun duplicateConfirmationTakesOwnershipBeforeTheCardIsSaved() {
        assertTrue(
            manualCardContentTransferred(
                saveAccepted = false,
                pendingDuplicateCandidate = submitted,
                submitted = submitted,
            ),
        )
    }

    @Test
    fun rejectedSaveWithoutMatchingHandoffKeepsOwnershipInTheEditor() {
        val unrelated = Card(name = "Other", data = "OTHER", format = PakaFormat.QR)

        assertFalse(
            manualCardContentTransferred(
                saveAccepted = false,
                pendingDuplicateCandidate = unrelated,
                submitted = submitted,
            ),
        )
    }
}
