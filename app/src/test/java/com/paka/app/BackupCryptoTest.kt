package com.paka.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCryptoTest {
    @Test
    fun encryptedBackupRoundTrips() {
        val payload = "passes and otp secrets \u0000 \u00ff".toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.encrypt(payload, "correct horse".toCharArray())
        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()))
    }

    @Test
    fun newBackupsUseSixHundredThousandRounds() {
        val encrypted = BackupCrypto.encrypt("secret".toByteArray(), "correct horse".toCharArray())
        assertEquals(600_000, ByteBuffer.wrap(encrypted, 6, 4).int)
    }

    @Test
    fun oldTwoHundredTenThousandRoundBackupsStillDecrypt() {
        val payload = "legacy backup".toByteArray()
        val passphrase = "correct horse".toCharArray()
        val encrypted = legacyEncrypt(payload, passphrase, 210_000)
        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, passphrase))
    }

    @Test
    fun weakNewBackupPassphrasesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt("secret".toByteArray(), "too short".toCharArray())
        }
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

    private fun legacyEncrypt(plaintext: ByteArray, passphrase: CharArray, iterations: Int): ByteArray {
        val magic = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 1)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val rounds = ByteBuffer.allocate(4).putInt(iterations).array()
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(magic + rounds + salt)
            magic + rounds + salt + iv + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }
}
