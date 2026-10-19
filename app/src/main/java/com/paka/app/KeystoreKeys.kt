package com.paka.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Creates AES-GCM keys with the strongest safe device-backed policy available. */
internal object KeystoreKeys {
    private const val KEYSTORE = "AndroidKeyStore"

    // Robolectric's JVM has no AndroidKeyStore provider. Unit tests install an
    // ephemeral software key factory here; production code never sets this.
    @Volatile
    internal var keyFactoryForTests: ((String) -> SecretKey)? = null

    @Synchronized
    fun getOrCreateAes256(alias: String): SecretKey {
        keyFactoryForTests?.let { return it(alias) }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(alias, strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // StrongBox is absent or cannot create this AES key.
            } catch (_: ProviderException) {
                // Some vendor providers report unavailable StrongBox generically.
            }
        }
        return generate(alias, strongBox = false)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(strongBox)
                }
                // Android documents destructive/authorization bugs on API 31–34.
                if (Build.VERSION.SDK_INT >= 35) {
                    setUnlockedDeviceRequired(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }
}

/**
 * Bulk PDF and photo bytes are encrypted in-process with a random data key that
 * is itself wrapped by a hardware-backed Keystore key. Keystore — and StrongBox
 * especially — is far too slow for megabytes of AES, while the CPU's AES
 * instructions are effectively free; the master key still never leaves the
 * hardware. Wrapped-key file: [PKD 1][12-byte IV][GCM ciphertext of 32 bytes].
 */
internal class WrappedDataKey(
    private val directory: String,
    private val fileName: String,
    private val keyAlias: String,
    private val aad: ByteArray,
) {
    @Volatile
    private var cached: SecretKey? = null

    fun get(context: Context): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(File(context.filesDir, directory), fileName)
            val master = KeystoreKeys.getOrCreateAes256(keyAlias)
            fun unwrap(blob: ByteArray): ByteArray {
                require(blob.size > MAGIC.size + IV_BYTES + TAG_BYTES) { "Data key is truncated" }
                require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Unsupported data key" }
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(TAG_BITS, blob, MAGIC.size, IV_BYTES))
                cipher.updateAAD(aad)
                return cipher.doFinal(blob, MAGIC.size + IV_BYTES, blob.size - MAGIC.size - IV_BYTES)
            }
            val raw = if (file.exists() || AtomicStore.backupFile(file).exists()) {
                AtomicStore.readWithBackup(file, ::unwrap).getOrElse {
                    // Regenerating here would silently orphan every envelope-
                    // encrypted file, so fail loudly instead.
                    throw IllegalStateException("Data key could not be unwrapped", it)
                }.value
            } else {
                ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes).also { fresh ->
                    val cipher = Cipher.getInstance(TRANSFORM)
                    cipher.init(Cipher.ENCRYPT_MODE, master)
                    cipher.updateAAD(aad)
                    AtomicStore.write(file, MAGIC + cipher.iv + cipher.doFinal(fresh)).getOrThrow()
                }
            }
            require(raw.size == KEY_BYTES) { "Invalid data key" }
            val key = SecretKeySpec(raw, "AES")
            raw.fill(0)
            cached = key
            return key
        }
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
        val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'D'.code.toByte(), 1)
    }
}
