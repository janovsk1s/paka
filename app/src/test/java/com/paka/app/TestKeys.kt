package com.paka.app

import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Ephemeral software AES keys standing in for AndroidKeyStore under Robolectric. */
internal object TestKeys {
    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        KeystoreKeys.keyFactoryForTests = { alias ->
            keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }
    }

    fun key(alias: String): SecretKey {
        install()
        return checkNotNull(KeystoreKeys.keyFactoryForTests).invoke(alias)
    }
}
