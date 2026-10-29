package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class BackupLimitTest {
    @Test
    fun storedBytesCountOnlyPrimaryEnvelopesAndSkipTheReimportedDocument() {
        val directory = Files.createTempDirectory("paka-limit").toFile()
        try {
            fun file(name: String, plaintextBytes: Int) =
                directory.resolve(name).writeBytes(ByteArray(plaintextBytes + 32))
            file("a.image.enc", 1_000)
            file("b.image.enc", 2_000)
            file("b.image.enc.bak", 2_000) // recovery generation, same document
            file("a.display.enc", 500) // display copy, not part of backups
            file("c.image.enc", 4_000)

            assertEquals(7_000L, storedPlaintextBytes(directory, ".image.enc", excludeIds = emptySet()))
            assertEquals(3_000L, storedPlaintextBytes(directory, ".image.enc", excludeIds = setOf("c")))
            // Swapping a pending pick: the one being replaced does not count either.
            assertEquals(1_000L, storedPlaintextBytes(directory, ".image.enc", excludeIds = setOf("b", "c")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
