package com.paka.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

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
