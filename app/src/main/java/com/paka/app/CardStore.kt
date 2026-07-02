package com.paka.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed encrypted persistence for passes, with plaintext migration. */
internal object CardStore {
    private const val KEY_ALIAS = "paka_card_key"
    private const val FILE = "cards.enc"
    private const val LEGACY_FILE = "cards.json"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val SCHEMA = 4
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'C'.code.toByte(), 1)
    private val AAD = "paka-cards-v1".toByteArray(Charsets.UTF_8)

    fun load(context: Context): LoadOutcome<List<Card>> {
        val encrypted = File(context.filesDir, FILE)
        if (encrypted.exists()) return loadEncrypted(encrypted)

        val legacy = File(context.filesDir, LEGACY_FILE)
        if (!legacy.exists() && !AtomicStore.backupFile(legacy).exists()) return LoadOutcome(emptyList())
        val legacyLoad = loadLegacy(legacy)
        if (!legacyLoad.writable) return legacyLoad

        return if (save(context, legacyLoad.value).isSuccess) {
            erasePlaintext(legacy)
            erasePlaintext(AtomicStore.backupFile(legacy))
            LoadOutcome(
                value = legacyLoad.value,
                warning = listOfNotNull(legacyLoad.warning, "Passes were migrated to encrypted storage.").joinToString(" "),
            )
        } else {
            LoadOutcome(
                value = legacyLoad.value,
                warning = "Passes were read but could not be encrypted. Plaintext was preserved and editing is disabled.",
                writable = false,
            )
        }
    }

    private fun loadEncrypted(file: File): LoadOutcome<List<Card>> {
        runCatching { return LoadOutcome(decrypt(AtomicStore.readBytes(file))) }
        val backup = AtomicStore.backupFile(file)
        if (backup.exists()) {
            runCatching {
                return LoadOutcome(
                    value = decrypt(AtomicStore.readBytes(backup)),
                    warning = "Passes were recovered from the previous encrypted backup.",
                )
            }
        }
        return LoadOutcome(
            value = emptyList(),
            warning = "Passes could not be decrypted. The encrypted file was preserved.",
            writable = false,
        )
    }

    private fun loadLegacy(file: File): LoadOutcome<List<Card>> {
        if (file.exists()) runCatching { return LoadOutcome(parse(AtomicStore.readBytes(file).toString(Charsets.UTF_8))) }
        val backup = AtomicStore.backupFile(file)
        if (backup.exists()) {
            runCatching {
                return LoadOutcome(
                    value = parse(AtomicStore.readBytes(backup).toString(Charsets.UTF_8)),
                    warning = "Passes were recovered from the previous plaintext backup.",
                )
            }
        }
        return LoadOutcome(
            value = emptyList(),
            warning = "Passes could not be read. The plaintext file was preserved.",
            writable = false,
        )
    }

    fun save(context: Context, cards: List<Card>): Result<Unit> = runCatching {
        val plaintext = serialize(cards).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(AAD)
            val cipherText = cipher.doFinal(plaintext)
            AtomicStore.write(File(context.filesDir, FILE), MAGIC + cipher.iv + cipherText).getOrThrow()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(blob: ByteArray): List<Card> {
        require(blob.size > MAGIC.size + 12 + 16) { "Encrypted pass store is truncated" }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Unsupported pass-store version" }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + 12)
        val cipherText = blob.copyOfRange(MAGIC.size + 12, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(cipherText)
        return try {
            parse(String(plaintext, Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun serialize(cards: List<Card>): String {
        val array = JSONArray()
        cards.forEach { card ->
            val item = JSONObject()
                .put("id", card.id)
                .put("name", card.name)
                .put("createdAt", card.createdAt)
                .put("notes", card.notes)
                .put("stack", card.stack ?: JSONObject.NULL)
                .put("references", JSONArray().also { references ->
                    require(card.references.size <= 2) { "A pass can have at most two references" }
                    card.references.forEach { reference -> references.put(serializeReference(reference)) }
                })
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
            array.put(item)
        }
        return JSONObject().put("schema", SCHEMA).put("cards", array).toString()
    }

    private fun parse(json: String): List<Card> {
        val root = json.trim()
        val schema: Int
        val array: JSONArray
        if (root.startsWith("[")) {
            schema = 1
            array = JSONArray(root)
        } else {
            val envelope = JSONObject(root)
            schema = envelope.getInt("schema")
            require(schema in 1..SCHEMA) { "Unsupported pass-store version" }
            array = envelope.getJSONArray("cards")
        }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val content = when (if (schema == 1) "barcode" else item.optString("type", "barcode")) {
                "barcode" -> PassContent.Barcode(
                    format = PakaFormat.valueOf(item.getString("format")),
                    data = item.getString("data").also { require(it.isNotBlank()) { "Invalid pass payload" } },
                )
                "pdf" -> PassContent.Pdf(
                    documentId = item.getString("documentId").also {
                        require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid PDF identifier" }
                    },
                    pageCount = item.getInt("pageCount").also { require(it in 1..1_000) { "Invalid PDF page count" } },
                )
                else -> error("Unsupported pass type")
            }
            Card(
                name = item.getString("name"),
                content = content,
                id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                notes = item.optString("notes", ""),
                stack = if (item.isNull("stack")) null else item.optString("stack", "").ifBlank { null },
                references = when {
                    schema >= 4 -> item.optJSONArray("references")?.let { references ->
                        require(references.length() <= 2) { "Too many pass references" }
                        (0 until references.length()).map { parseReference(references.getJSONObject(it)) }
                    }.orEmpty()
                    schema == 3 && !item.isNull("reference") -> listOf(parseReference(item.getJSONObject("reference")))
                    else -> emptyList()
                },
            )
        }
    }

    private fun serializeReference(reference: PassReference): JSONObject = JSONObject()
        .put("uri", reference.uri)
        .put("name", reference.name)
        .put("mimeType", reference.mimeType)

    private fun parseReference(reference: JSONObject): PassReference = PassReference(
        uri = reference.getString("uri").also {
            require(it.startsWith("content://") && it.length <= 8_192) { "Invalid reference URI" }
        },
        name = reference.getString("name").also {
            require(it.isNotBlank() && it.length <= 512) { "Invalid reference name" }
        },
        mimeType = reference.optString("mimeType", "application/octet-stream").also {
            require(it.isNotBlank() && it.length <= 256) { "Invalid reference type" }
        },
    )

    private fun erasePlaintext(file: File) {
        if (!file.exists()) return
        runCatching {
            val length = file.length()
            file.outputStream().use { output ->
                val zeros = ByteArray(8 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    output.write(zeros, 0, count)
                    remaining -= count
                }
                output.flush()
                output.fd.sync()
            }
        }
        file.delete()
    }
}
