package com.paka.app

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class BackupData(val cards: List<Card>, val accounts: List<OtpAccount>)

/** Portable, passphrase-encrypted backup containing both Paka data stores. */
internal object BackupStore {
    const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
    private const val SCHEMA = 1
    private const val MAX_ITEMS = 10_000
    private const val MAX_TEXT = 1_000_000

    fun encrypt(cards: List<Card>, accounts: List<OtpAccount>, passphrase: CharArray): ByteArray {
        val payload = serialize(cards, accounts).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_BACKUP_BYTES) { "Backup is too large" }
        return try {
            BackupCrypto.encrypt(payload, passphrase)
        } finally {
            payload.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): BackupData {
        require(blob.size <= MAX_BACKUP_BYTES) { "Backup is too large" }
        val plaintext = BackupCrypto.decrypt(blob, passphrase)
        require(plaintext.size <= MAX_BACKUP_BYTES) { "Backup is too large" }
        return try {
            parse(String(plaintext, Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun serialize(cards: List<Card>, accounts: List<OtpAccount>): String {
        val cardArray = JSONArray()
        cards.forEach { card ->
            cardArray.put(
                JSONObject()
                    .put("id", card.id)
                    .put("name", card.name)
                    .put("data", card.data)
                    .put("format", card.format.name)
                    .put("createdAt", card.createdAt)
                    .put("notes", card.notes)
                    .put("stack", card.stack ?: JSONObject.NULL),
            )
        }
        val accountArray = JSONArray()
        accounts.forEach { account ->
            accountArray.put(
                JSONObject()
                    .put("id", account.id)
                    .put("issuer", account.issuer)
                    .put("account", account.account)
                    .put("secret", account.secret)
                    .put("digits", account.digits)
                    .put("period", account.period)
                    .put("algorithm", account.algorithm)
                    .put("createdAt", account.createdAt),
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("cards", cardArray)
            .put("accounts", accountArray)
            .toString()
    }

    private fun parse(json: String): BackupData {
        val root = JSONObject(json)
        require(root.getInt("schema") == SCHEMA) { "Unsupported backup version" }
        val cardArray = root.getJSONArray("cards")
        val accountArray = root.getJSONArray("accounts")
        require(cardArray.length() <= MAX_ITEMS && accountArray.length() <= MAX_ITEMS) { "Backup has too many entries" }

        val cards = (0 until cardArray.length()).map { index ->
            val item = cardArray.getJSONObject(index)
            val name = limited(item.getString("name"))
            val data = limited(item.getString("data"))
            val id = limited(item.getString("id"))
            require(name.isNotBlank() && data.isNotBlank() && id.isNotBlank()) { "Invalid pass entry" }
            Card(
                name = name,
                data = data,
                format = PakaFormat.valueOf(item.getString("format")),
                id = id,
                createdAt = item.getLong("createdAt"),
                notes = limited(item.optString("notes", "")),
                stack = if (item.isNull("stack")) null else limited(item.optString("stack", "")).ifBlank { null },
            )
        }
        val accounts = (0 until accountArray.length()).map { index ->
            val item = accountArray.getJSONObject(index)
            val id = limited(item.getString("id"))
            require(id.isNotBlank()) { "Invalid 2FA entry" }
            OtpAccount(
                issuer = limited(item.getString("issuer")),
                account = limited(item.optString("account", "")),
                secret = limited(item.getString("secret")),
                digits = item.getInt("digits"),
                period = item.getInt("period"),
                algorithm = limited(item.getString("algorithm")),
                id = id,
                createdAt = item.getLong("createdAt"),
            ).also { require(Totp.validationError(it) == null) { "Invalid 2FA entry" } }
        }
        require(cards.map { it.id }.toSet().size == cards.size) { "Duplicate pass identifiers" }
        require(accounts.map { it.id }.toSet().size == accounts.size) { "Duplicate 2FA identifiers" }
        return BackupData(cards, accounts)
    }

    private fun limited(value: String): String {
        require(value.length <= MAX_TEXT) { "Backup text is too large" }
        return value
    }
}

/** Versioned authenticated encryption container: magic, rounds, salt, IV, ciphertext+tag. */
internal object BackupCrypto {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 1)
    private const val ITERATIONS = 210_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val HEADER_BYTES = 6 + 4 + SALT_BYTES + IV_BYTES

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 8) { "Passphrase must be at least 8 characters" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val rounds = ByteBuffer.allocate(4).putInt(ITERATIONS).array()
        val aad = MAGIC + rounds + salt
        val key = derive(passphrase, salt, ITERATIONS)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            MAGIC + rounds + salt + iv + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        require(blob.size > HEADER_BYTES + TAG_BITS / 8) { "Backup is truncated" }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a Paka backup" }
        val buffer = ByteBuffer.wrap(blob)
        buffer.position(MAGIC.size)
        val iterations = buffer.int
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid backup parameters" }
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val cipherText = ByteArray(buffer.remaining()).also(buffer::get)
        val rounds = ByteBuffer.allocate(4).putInt(iterations).array()
        val key = derive(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(MAGIC + rounds + salt)
            cipher.doFinal(cipherText)
        } catch (badTag: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect passphrase or damaged backup", badTag)
        } finally {
            key.fill(0)
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
