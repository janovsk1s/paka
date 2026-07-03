package com.paka.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AtomicStoreTest {
    @Test
    fun repeatedWriteKeepsCurrentAndPreviousReadableGeneration() = withTempDirectory { directory ->
        val file = directory.resolve("cards.enc")
        val first = "first generation".toByteArray()
        val second = "second generation".toByteArray()

        AtomicStore.write(file, first).getOrThrow()
        AtomicStore.write(file, second).getOrThrow()

        assertArrayEquals(second, AtomicStore.readBytes(file))
        assertArrayEquals(first, AtomicStore.readBytes(AtomicStore.backupFile(file)))
        assertFalse(directory.resolve("cards.enc.tmp").exists())
    }

    @Test
    fun corruptPrimaryRecoversFromPreviousGeneration() = withTempDirectory { directory ->
        val file = directory.resolve("otp.enc")
        file.writeText("truncated")
        AtomicStore.backupFile(file).writeText("VALID:backup")

        val recovered = AtomicStore.readWithBackup(file, ::decodeTestValue).getOrThrow()

        assertEquals("backup", recovered.value)
        assertTrue(recovered.fromBackup)
    }

    @Test
    fun corruptPrimaryAndBackupFailWithoutChangingEitherFile() = withTempDirectory { directory ->
        val file = directory.resolve("otp.enc")
        val backup = AtomicStore.backupFile(file)
        file.writeText("broken primary")
        backup.writeText("broken backup")
        val primaryBefore = file.readBytes()
        val backupBefore = backup.readBytes()

        assertTrue(AtomicStore.readWithBackup(file, ::decodeTestValue).isFailure)
        assertArrayEquals(primaryBefore, file.readBytes())
        assertArrayEquals(backupBefore, backup.readBytes())
    }

    @Test
    fun validPrimaryDoesNotReadBackup() = withTempDirectory { directory ->
        val file = directory.resolve("cards.enc")
        file.writeText("VALID:primary")
        AtomicStore.backupFile(file).writeText("VALID:backup")

        val recovered = AtomicStore.readWithBackup(file, ::decodeTestValue).getOrThrow()

        assertEquals("primary", recovered.value)
        assertFalse(recovered.fromBackup)
    }

    private fun decodeTestValue(bytes: ByteArray): String {
        val value = bytes.toString(Charsets.UTF_8)
        require(value.startsWith("VALID:")) { "corrupt value" }
        return value.removePrefix("VALID:")
    }

    private fun withTempDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("paka-atomic-store-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
