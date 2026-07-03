package com.paka.app

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class BackupData(
    val cards: List<Card>,
    val accounts: List<OtpAccount>,
    val documents: Map<String, ByteArray> = emptyMap(),
) {
    fun clearDocuments() = documents.values.forEach { it.fill(0) }
}

/** Portable, passphrase-encrypted backup containing both Paka data stores. */
internal object BackupStore {
    const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    private const val MAX_PDF_TOTAL_BYTES = 20 * 1024 * 1024
    private const val SCHEMA = 2
    private const val MAX_ITEMS = 10_000
    private const val MAX_TEXT = 1_000_000

    fun encrypt(
        cards: List<Card>,
        accounts: List<OtpAccount>,
        passphrase: CharArray,
        documents: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val payload = serialize(cards, accounts, documents).toByteArray(Charsets.UTF_8)
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

    private fun serialize(cards: List<Card>, accounts: List<OtpAccount>, documents: Map<String, ByteArray>): String {
        val cardArray = JSONArray()
        cards.forEach { card ->
            val item = JSONObject()
                .put("id", card.id)
                .put("name", card.name)
                .put("createdAt", card.createdAt)
                .put("notes", card.notes)
                .put("stack", card.stack ?: JSONObject.NULL)
            when (val content = card.content) {
                is PassContent.Barcode -> item
                    .put("type", "barcode")
                    .put("data", content.data)
                    .put("format", content.format.name)
                is PassContent.Pdf -> item
                    .put("type", "pdf")
                    .put("documentId", content.documentId)
                    .put("pageCount", content.pageCount)
            }
            cardArray.put(item)
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
        val referencedDocuments = cards.mapNotNull { it.pdfContent?.documentId }.toSet()
        require(documents.keys == referencedDocuments) { "Backup is missing PDF data" }
        require(documents.values.sumOf { it.size.toLong() } <= MAX_PDF_TOTAL_BYTES) { "PDF backup data is too large" }
        val documentArray = JSONArray()
        documents.forEach { (id, bytes) ->
            require(bytes.size <= PdfStore.MAX_PDF_BYTES && pdfDocumentId(bytes) == id) { "Invalid PDF backup data" }
            documentArray.put(
                JSONObject()
                    .put("id", id)
                    .put("data", Base64.getEncoder().encodeToString(bytes)),
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("cards", cardArray)
            .put("accounts", accountArray)
            .put("documents", documentArray)
            .toString()
    }

    private fun parse(json: String): BackupData {
        val root = JSONObject(json)
        val schema = root.getInt("schema")
        require(schema in 1..SCHEMA) { "Unsupported backup version" }
        val cardArray = root.getJSONArray("cards")
        val accountArray = root.getJSONArray("accounts")
        require(cardArray.length() <= MAX_ITEMS && accountArray.length() <= MAX_ITEMS) { "Backup has too many entries" }

        val cards = (0 until cardArray.length()).map { index ->
            val item = cardArray.getJSONObject(index)
            val name = limited(item.getString("name"))
            val id = limited(item.getString("id"))
            require(name.isNotBlank() && id.isNotBlank()) { "Invalid pass entry" }
            val content = when (if (schema == 1) "barcode" else item.optString("type", "barcode")) {
                "barcode" -> PassContent.Barcode(
                    format = PakaFormat.valueOf(item.getString("format")),
                    data = limited(item.getString("data")).also { require(it.isNotBlank()) { "Invalid pass entry" } },
                )
                "pdf" -> PassContent.Pdf(
                    documentId = limited(item.getString("documentId")).also {
                        require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid PDF identifier" }
                    },
                    pageCount = item.getInt("pageCount").also { require(it in 1..1_000) { "Invalid PDF page count" } },
                )
                else -> error("Unsupported pass type")
            }
            Card(
                name = name,
                content = content,
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
        val documents = linkedMapOf<String, ByteArray>()
        try {
            if (schema >= 2) {
                val documentArray = root.optJSONArray("documents") ?: JSONArray()
                require(documentArray.length() <= MAX_ITEMS) { "Backup has too many documents" }
                var totalDocumentBytes = 0L
                for (index in 0 until documentArray.length()) {
                    val item = documentArray.getJSONObject(index)
                    val id = limited(item.getString("id"))
                    require(id.matches(Regex("[0-9a-f]{64}")) && id !in documents) { "Invalid PDF backup entry" }
                    val encoded = item.getString("data")
                    require(encoded.length <= ((PdfStore.MAX_PDF_BYTES + 2L) / 3L * 4L + 4L).toInt()) { "PDF backup entry is too large" }
                    val bytes = Base64.getDecoder().decode(encoded)
                    require(bytes.size <= PdfStore.MAX_PDF_BYTES && pdfDocumentId(bytes) == id) { "Invalid PDF backup entry" }
                    totalDocumentBytes += bytes.size
                    require(totalDocumentBytes <= MAX_PDF_TOTAL_BYTES) { "PDF backup data is too large" }
                    documents[id] = bytes
                }
            }
            require(cards.map { it.id }.toSet().size == cards.size) { "Duplicate pass identifiers" }
            require(accounts.map { it.id }.toSet().size == accounts.size) { "Duplicate 2FA identifiers" }
            require(cards.mapNotNull { it.pdfContent?.documentId }.toSet() == documents.keys) { "Backup is missing PDF data" }
            return BackupData(cards, accounts, documents)
        } catch (error: Throwable) {
            documents.values.forEach { it.fill(0) }
            throw error
        }
    }

    private fun limited(value: String): String {
        require(value.length <= MAX_TEXT) { "Backup text is too large" }
        return value
    }

}

/** Versioned authenticated encryption container: magic, rounds, salt, IV, ciphertext+tag. */
internal object BackupCrypto {
    const val MIN_NEW_PASSPHRASE_LENGTH = 12
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 1)
    private const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val HEADER_BYTES = 6 + 4 + SALT_BYTES + IV_BYTES

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_NEW_PASSPHRASE_LENGTH) {
            "Passphrase must be at least $MIN_NEW_PASSPHRASE_LENGTH characters"
        }
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
