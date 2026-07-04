package com.paka.app

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

internal data class BinaryBackupPayload(
    val metadata: ByteArray,
    val documents: Map<String, ByteArray>,
    val photos: Map<String, ByteArray>,
) {
    fun clear() {
        metadata.fill(0)
        documents.values.forEach { it.fill(0) }
        photos.values.forEach { it.fill(0) }
    }
}

internal enum class BackupPayloadFormat { LEGACY_SCHEMA_1, LEGACY_SCHEMA_2, BINARY_PHOTOS }

internal fun selectBackupPayloadFormat(hasDocuments: Boolean, hasPhotos: Boolean): BackupPayloadFormat = when {
    hasPhotos -> BackupPayloadFormat.BINARY_PHOTOS
    hasDocuments -> BackupPayloadFormat.LEGACY_SCHEMA_2
    else -> BackupPayloadFormat.LEGACY_SCHEMA_1
}

/** Compact payload inside BackupCrypto. Raw blobs avoid Base64 and JSON expansion. */
internal object BackupPayloadCodec {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(), '4'.code.toByte())
    private const val VERSION = 1
    private const val ID_BYTES = 64
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    fun isBinary(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encode(
        metadata: ByteArray,
        documents: Map<String, ByteArray>,
        photos: Map<String, ByteArray>,
        maxBytes: Int,
    ): ByteArray {
        require(metadata.size <= MAX_METADATA_BYTES) { "Backup metadata is too large" }
        require(documents.size <= MAX_ENTRIES && photos.size <= MAX_ENTRIES) { "Backup has too many blobs" }
        val estimated = MAGIC.size + 4 + 4 + metadata.size + 4 + 4 +
            documents.entries.sumOf { ID_BYTES.toLong() + 4 + it.value.size } +
            photos.entries.sumOf { ID_BYTES.toLong() + 4 + it.value.size }
        require(estimated <= maxBytes) { "Backup is too large" }
        return ByteBuffer.allocate(estimated.toInt()).apply {
            put(MAGIC)
            putInt(VERSION)
            putInt(metadata.size)
            put(metadata)
            writeEntries(this, documents)
            writeEntries(this, photos)
        }.array()
    }

    fun decode(
        bytes: ByteArray,
        maxBytes: Int,
        maxDocumentBytes: Int,
        maxPhotoBytes: Int,
        maxDocumentTotalBytes: Int,
        maxPhotoTotalBytes: Int,
    ): BinaryBackupPayload {
        require(bytes.size <= maxBytes && isBinary(bytes)) { "Invalid binary backup" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC) && input.readInt() == VERSION) { "Unsupported binary backup" }
        val metadataLength = input.readInt()
        require(metadataLength in 0..MAX_METADATA_BYTES && metadataLength <= input.available()) { "Invalid backup metadata" }
        val metadata = ByteArray(metadataLength).also(input::readFully)
        val documents = linkedMapOf<String, ByteArray>()
        val photos = linkedMapOf<String, ByteArray>()
        return try {
            readEntries(input, documents, maxDocumentBytes, maxDocumentTotalBytes)
            readEntries(input, photos, maxPhotoBytes, maxPhotoTotalBytes)
            require(input.available() == 0) { "Backup contains trailing data" }
            BinaryBackupPayload(metadata, documents, photos)
        } catch (error: Throwable) {
            metadata.fill(0)
            documents.values.forEach { it.fill(0) }
            photos.values.forEach { it.fill(0) }
            throw error
        }
    }

    private fun writeEntries(output: ByteBuffer, entries: Map<String, ByteArray>) {
        output.putInt(entries.size)
        entries.forEach { (id, bytes) ->
            require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid backup blob identifier" }
            output.put(id.toByteArray(Charsets.US_ASCII))
            output.putInt(bytes.size)
            output.put(bytes)
        }
    }

    private fun readEntries(
        input: DataInputStream,
        destination: MutableMap<String, ByteArray>,
        maxEntryBytes: Int,
        maxTotalBytes: Int,
    ) {
        val count = input.readInt()
        require(count in 0..MAX_ENTRIES) { "Backup has too many blobs" }
        var total = 0L
        repeat(count) {
            require(input.available() >= ID_BYTES + 4) { "Backup blob entry is truncated" }
            val id = ByteArray(ID_BYTES).also(input::readFully).toString(Charsets.US_ASCII)
            require(id.matches(Regex("[0-9a-f]{64}")) && id !in destination) { "Invalid backup blob identifier" }
            val length = input.readInt()
            require(length in 0..maxEntryBytes && length <= input.available()) { "Backup blob is too large or truncated" }
            total += length
            require(total <= maxTotalBytes) { "Backup blob data is too large" }
            destination[id] = ByteArray(length).also(input::readFully)
        }
    }
}
