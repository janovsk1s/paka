package com.paka.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun encryptedBackupRoundTrips() {
        val payload = "passes and otp secrets \u0000 \u00ff".toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.encrypt(payload, "correct horse".toCharArray())
        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val encrypted = BackupCrypto.encrypt("secret".toByteArray(), "correct horse".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(encrypted, "wrong password".toCharArray())
        }
    }

    @Test
    fun tamperingIsRejected() {
        val encrypted = BackupCrypto.encrypt("secret".toByteArray(), "correct horse".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(encrypted, "correct horse".toCharArray())
        }
    }
}
