package com.paka.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed encrypted persistence for passes, with plaintext migration. */
internal object CardStore {
    private const val KEY_ALIAS = "paka_card_key"
    private const val FILE = "cards.enc"
    private const val LEGACY_FILE = "cards.json"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val SCHEMA = 5
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'C'.code.toByte(), 1)
    private val AAD = "paka-cards-v1".toByteArray(Charsets.UTF_8)

    fun load(context: Context): LoadOutcome<List<Card>> {
        val encrypted = storageFile(context)
        if (encrypted.exists() || AtomicStore.backupFile(encrypted).exists()) return loadEncrypted(context, encrypted)

        val legacy = File(context.filesDir, LEGACY_FILE)
        if (!legacy.exists() && !AtomicStore.backupFile(legacy).exists()) return LoadOutcome(emptyList())
        val legacyLoad = loadLegacy(context, legacy)
        if (!legacyLoad.writable) return legacyLoad

        return if (save(context, legacyLoad.value).isSuccess) {
            erasePlaintext(legacy)
            erasePlaintext(AtomicStore.backupFile(legacy))
            erasePlaintext(AtomicStore.corruptFile(legacy))
            LoadOutcome(
                value = legacyLoad.value,
                warning = listOfNotNull(
                    legacyLoad.warning,
                    context.getString(R.string.store_passes_migrated),
                ).joinToString(" "),
            )
        } else {
            LoadOutcome(
                value = legacyLoad.value,
                warning = context.getString(R.string.store_passes_encrypt_failed),
                writable = false,
            )
        }
    }

    private fun loadEncrypted(context: Context, file: File): LoadOutcome<List<Card>> {
        AtomicStore.readWithBackup(file, ::decrypt).getOrNull()?.let { recovered ->
            return LoadOutcome(
                value = recovered.value,
                warning = if (recovered.fromBackup) {
                    context.getString(R.string.store_passes_encrypted_recovered)
                } else {
                    null
                },
            )
        }
        return LoadOutcome(
            value = emptyList(),
            warning = context.getString(R.string.store_passes_decrypt_failed),
            writable = false,
        )
    }

    private fun loadLegacy(context: Context, file: File): LoadOutcome<List<Card>> {
        AtomicStore.readWithBackup(file) { parse(it.toString(Charsets.UTF_8)) }.getOrNull()?.let { recovered ->
            return LoadOutcome(
                value = recovered.value,
                warning = if (recovered.fromBackup) {
                    context.getString(R.string.store_passes_plaintext_recovered)
                } else {
                    null
                },
            )
        }
        return LoadOutcome(
            value = emptyList(),
            warning = context.getString(R.string.store_passes_read_failed),
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
            AtomicStore.write(storageFile(context), MAGIC + cipher.iv + cipherText).getOrThrow()
        } finally {
            plaintext.fill(0)
        }
    }

    internal fun storageFile(context: Context): File = File(context.filesDir, FILE)

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

    private fun secretKey(): SecretKey = KeystoreKeys.getOrCreateAes256(KEY_ALIAS)

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
                is PassContent.Photos -> item
                    .put("type", "photos")
                    .put("pages", JSONArray().also { pages ->
                        content.pages.forEach { page ->
                            pages.put(
                                JSONObject()
                                    .put("documentId", page.documentId)
                                    .put("width", page.width)
                                    .put("height", page.height),
                            )
                        }
                    })
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
                "photos" -> PassContent.Photos(
                    pages = item.getJSONArray("pages").let { pages ->
                        require(pages.length() in 1..2) { "Invalid photo count" }
                        (0 until pages.length()).map { pageIndex ->
                            val page = pages.getJSONObject(pageIndex)
                            PhotoPage(
                                documentId = page.getString("documentId").also {
                                    require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid photo identifier" }
                                },
                                width = page.getInt("width").also { require(it in 1..PhotoStore.MAX_DIMENSION) },
                                height = page.getInt("height").also { require(it in 1..PhotoStore.MAX_DIMENSION) },
                            )
                        }
                    },
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
