package com.paka.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted on-device storage for 2FA accounts. Secrets are encrypted with an
 * AES-256-GCM key held in the Android Keystore (hardware-backed where available)
 * and never leave the device. Versioned file layout: [magic][12-byte IV][GCM ciphertext].
 */
internal object SecureStore {
    private const val KEY_ALIAS = "paka_otp_key"
    private const val FILE = "otp.enc"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val SCHEMA = 1
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte(), 1)
    private val AAD = "paka-otp-v1".toByteArray(Charsets.UTF_8)

    private fun secretKey(): SecretKey = KeystoreKeys.getOrCreateAes256(KEY_ALIAS)

    fun loadAccounts(context: Context): LoadOutcome<List<OtpAccount>> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return LoadOutcome(emptyList())

        AtomicStore.readWithBackup(file, ::decrypt).getOrNull()?.let { recovered ->
            // A pre-versioned store carries no AAD binding. Re-encrypt it in the
            // current layout now instead of waiting for the next user edit.
            if (!recovered.value.versioned && !recovered.fromBackup) {
                saveAccounts(context, recovered.value.accounts)
            }
            return LoadOutcome(
                value = recovered.value.accounts,
                warning = if (recovered.fromBackup) "2FA accounts were recovered from the previous backup." else null,
            )
        }
        return LoadOutcome(
            value = emptyList(),
            warning = "2FA accounts could not be decrypted. The encrypted file was preserved.",
            writable = false,
        )
    }

    private data class DecodedStore(val accounts: List<OtpAccount>, val versioned: Boolean)

    private fun decrypt(blob: ByteArray): DecodedStore {
        require(blob.size > 28) { "Encrypted store is truncated" }
        val versioned = blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        val ivOffset = if (versioned) MAGIC.size else 0
        val iv = blob.copyOfRange(ivOffset, ivOffset + 12)
        val cipherText = blob.copyOfRange(ivOffset + 12, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        if (versioned) cipher.updateAAD(AAD)
        return DecodedStore(parse(String(cipher.doFinal(cipherText), Charsets.UTF_8)), versioned)
    }

    fun saveAccounts(context: Context, accounts: List<OtpAccount>): Result<Unit> =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(AAD)
            val iv = cipher.iv // 12 bytes for GCM
            val cipherText = cipher.doFinal(serialize(accounts).toByteArray(Charsets.UTF_8))
            AtomicStore.write(File(context.filesDir, FILE), MAGIC + iv + cipherText).getOrThrow()
        }

    private fun serialize(accounts: List<OtpAccount>): String {
        val arr = JSONArray()
        for (a in accounts) {
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("issuer", a.issuer)
                    .put("account", a.account)
                    .put("secret", a.secret)
                    .put("digits", a.digits)
                    .put("period", a.period)
                    .put("algorithm", a.algorithm)
                    .put("createdAt", a.createdAt),
            )
        }
        return JSONObject().put("schema", SCHEMA).put("accounts", arr).toString()
    }

    private fun parse(json: String): List<OtpAccount> {
        val root = json.trim()
        val arr = if (root.startsWith("[")) {
            JSONArray(root) // v0 compatibility
        } else {
            val envelope = JSONObject(root)
            require(envelope.getInt("schema") == SCHEMA) { "Unsupported OTP-store version" }
            envelope.getJSONArray("accounts")
        }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OtpAccount(
                issuer = o.getString("issuer"),
                account = o.optString("account", ""),
                secret = o.getString("secret"),
                digits = o.optInt("digits", 6),
                period = o.optInt("period", 30),
                algorithm = o.optString("algorithm", "SHA1"),
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}
