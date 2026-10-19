package com.paka.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.ceil
import kotlin.math.max

internal data class PhotoImport(
    val page: PhotoPage,
    val created: Boolean,
)

/** Encrypted originals for one- and two-sided document-photo passes. */
internal object PhotoStore {
    const val MAX_PHOTO_BYTES = 10 * 1024 * 1024
    const val MAX_DIMENSION = 20_000
    const val MAX_PIXELS = 48_000_000L
    private const val DIRECTORY = "photos"
    private const val SUFFIX = ".image.enc"
    private const val DISPLAY_SUFFIX = ".display.enc"
    private const val DISPLAY_MAX_DIMENSION = 2_400
    private const val KEY_ALIAS = "paka_photo_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val DOCUMENT_LOCK_STRIPES = 64

    // Version 1 encrypted bulk bytes directly with the Keystore key; version 2
    // uses the in-process data key. Both are 4 bytes so offsets are identical.
    private val MAGIC_KEYSTORE = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 1)
    private val MAGIC_DEK = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 2)
    private val dataKey = WrappedDataKey(
        directory = DIRECTORY,
        fileName = "photo.dek",
        keyAlias = KEY_ALIAS,
        aad = "paka-photo-dek-v1".toByteArray(Charsets.UTF_8),
    )

    // Fixed lock stripes rather than one object per documentId, which would
    // grow for the process lifetime with every photo ever decoded.
    private val documentLocks = Array(DOCUMENT_LOCK_STRIPES) { Any() }

    // Serialises fallback display-copy creation and decryption per photo.
    private inline fun <T> withDocumentLock(documentId: String, block: () -> T): T =
        synchronized(documentLocks[(documentId.hashCode() and Int.MAX_VALUE) % documentLocks.size]) { block() }

    /** Reads and validates an import candidate without storing it. The caller zeroes the returned bytes. */
    fun readImportBytes(context: Context, uri: Uri): Result<ByteArray> = runCatching {
        readUri(context, uri).also { inspect(it) }
    }

    /**
     * Ingests photo bytes already in memory. The caller zeroes them. Pending
     * imports in [replacing] are about to be deleted, so they do not count.
     */
    fun importBytes(
        context: Context,
        bytes: ByteArray,
        replacing: Set<String> = emptySet(),
    ): Result<PhotoImport> = runCatching {
        val dimensions = inspect(bytes)
        val documentId = documentDigest(bytes)
        val stored = storedPlaintextBytes(File(context.filesDir, DIRECTORY), SUFFIX, replacing + documentId)
        if (stored + bytes.size > BackupStore.MAX_PHOTO_TOTAL_BYTES) throw BackupLimitException()
        val file = documentFile(context, documentId)
        val created = !file.exists()
        // inspect() and the content-derived identifier verify these bytes, so
        // do not rotate a corrupt primary over an existing healthy backup.
        encryptToFile(context, file, aad(documentId), bytes, replaceVerified = true)
        runCatching { writeDisplayCopy(context, documentId, bytes) }
        PhotoImport(PhotoPage(documentId, dimensions.first, dimensions.second), created)
    }

    /** Returns a decoded display bitmap owned by the active viewer or stack. */
    fun decode(context: Context, documentId: String, targetWidth: Int, targetHeight: Int): Bitmap {
        return withDocumentLock(documentId) {
            val bytes = displayPlaintext(context, documentId)
            try {
                // Hardware bitmaps live in GPU memory, keeping pinch-zoom smooth.
                decodeBytes(bytes, targetWidth, targetHeight, allowHardware = true)
            } finally {
                bytes.fill(0)
            }
        }
    }

    fun delete(context: Context, documentId: String): Boolean =
        listOf(documentFile(context, documentId), displayFile(context, documentId))
            .map(AtomicStore::delete)
            .all { it }

    fun deleteOrphans(context: Context, referencedIds: Set<String>) {
        File(context.filesDir, DIRECTORY).listFiles()?.forEach { file ->
            val name = atomicBaseName(file.name)
            val documentId = when {
                name.endsWith(DISPLAY_SUFFIX) -> name.removeSuffix(DISPLAY_SUFFIX)
                name.endsWith(SUFFIX) -> name.removeSuffix(SUFFIX)
                else -> return@forEach
            }
            if (documentId !in referencedIds) {
                if (documentId.matches(DOCUMENT_ID_PATTERN)) delete(context, documentId) else file.delete()
            }
        }
    }

    fun readPlaintext(context: Context, documentId: String): ByteArray =
        decryptFile(context, documentFile(context, documentId), aad(documentId))

    fun writePlaintext(context: Context, documentId: String, bytes: ByteArray) {
        require(bytes.size <= MAX_PHOTO_BYTES) { "Photo is too large" }
        require(documentDigest(bytes) == documentId) { "Photo identifier does not match its content" }
        inspect(bytes)
        encryptToFile(
            context,
            documentFile(context, documentId),
            aad(documentId),
            bytes,
            replaceVerified = true,
        )
        runCatching { writeDisplayCopy(context, documentId, bytes) }
    }

    /**
     * Returns the pre-scaled display copy, creating it from the original for
     * photos imported before display copies existed (or if it was corrupted).
     * Viewing decodes only this small JPEG; the original stays for backups.
     */
    private fun displayPlaintext(context: Context, documentId: String): ByteArray {
        val display = displayFile(context, documentId)
        if (display.exists()) {
            runCatching { return decryptFile(context, display, displayAad(documentId)) }
        }
        val original = decryptFile(context, documentFile(context, documentId), aad(documentId))
        return try {
            buildDisplayBytes(original).also {
                runCatching { encryptToFile(context, display, displayAad(documentId), it) }
            }
        } finally {
            original.fill(0)
        }
    }

    private fun writeDisplayCopy(context: Context, documentId: String, original: ByteArray) {
        val display = buildDisplayBytes(original)
        try {
            encryptToFile(context, displayFile(context, documentId), displayAad(documentId), display)
        } finally {
            display.fill(0)
        }
    }

    // BitmapFactory does not apply EXIF on API 26/27. Share review's
    // orientation-aware bounded path so mirrored and rotated originals are
    // displayed consistently without rewriting the encrypted original.
    private fun buildDisplayBytes(original: ByteArray): ByteArray =
        CapturedPhoto.encode(CapturedPhoto.decodePreview(original, DISPLAY_MAX_DIMENSION))

    internal fun inspect(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PHOTO_BYTES) { "Photo is too large" }
        require(hasSupportedHeader(bytes)) { "The selected file is not a supported image" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image" }
        decodeBytes(bytes, 96, 96).recycle()
        val dimensions = CapturedPhoto.orientedDimensions(
            bytes,
            bounds.outWidth,
            bounds.outHeight,
        )
        require(dimensions.first in 1..MAX_DIMENSION && dimensions.second in 1..MAX_DIMENSION) {
            "Photo dimensions are unsupported"
        }
        require(dimensions.first.toLong() * dimensions.second <= MAX_PIXELS) { "Photo has too many pixels" }
        return dimensions
    }

    internal fun hasSupportedHeader(bytes: ByteArray): Boolean {
        fun ascii(offset: Int, value: String): Boolean =
            offset + value.length <= bytes.size && value.indices.all { bytes[offset + it] == value[it].code.toByte() }
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return true
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return true
        if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) return true
        if (ascii(0, "RIFF") && ascii(8, "WEBP")) return true
        if (ascii(4, "ftyp") && bytes.size >= 12) {
            val brand = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) return true
        }
        return false
    }

    private fun decodeBytes(
        bytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        allowHardware: Boolean = false,
    ): Bitmap {
        val maxWidth = targetWidth.coerceAtLeast(1)
        val maxHeight = targetHeight.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "Photo dimensions are unsupported" }
                require(width.toLong() * height <= MAX_PIXELS) { "Photo has too many pixels" }
                val sample = ceil(max(width.toDouble() / maxWidth, height.toDouble() / maxHeight))
                    .toInt().coerceAtLeast(1)
                decoder.setTargetSampleSize(sample)
                decoder.allocator =
                    if (allowHardware) ImageDecoder.ALLOCATOR_DEFAULT else ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image" }
        require(bounds.outWidth <= MAX_DIMENSION && bounds.outHeight <= MAX_DIMENSION) { "Photo dimensions are unsupported" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) { "Photo has too many pixels" }
        var sample = 1
        while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("The selected file is not a supported image")
    }

    private fun readUri(context: Context, uri: Uri): ByteArray = UriBytes.read(
        context,
        uri,
        MAX_PHOTO_BYTES,
        tooLarge = "Photo is larger than 10 MB",
        unreadable = "Photo could not be opened",
    )

    private fun documentFile(context: Context, documentId: String): File {
        require(documentId.matches(DOCUMENT_ID_PATTERN)) { "Invalid photo identifier" }
        return File(File(context.filesDir, DIRECTORY), "$documentId$SUFFIX")
    }

    private fun displayFile(context: Context, documentId: String): File {
        require(documentId.matches(DOCUMENT_ID_PATTERN)) { "Invalid photo identifier" }
        return File(File(context.filesDir, DIRECTORY), "$documentId$DISPLAY_SUFFIX")
    }

    private fun encryptToFile(
        context: Context,
        file: File,
        aad: ByteArray,
        plaintext: ByteArray,
        replaceVerified: Boolean = false,
    ) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey.get(context))
        cipher.updateAAD(aad)
        val encrypted = MAGIC_DEK + cipher.iv + cipher.doFinal(plaintext)
        if (replaceVerified) AtomicStore.replaceVerified(file, encrypted).getOrThrow()
        else AtomicStore.write(file, encrypted).getOrThrow()
    }

    private fun decryptFile(context: Context, file: File, aad: ByteArray): ByteArray {
        var legacy = false
        fun decrypt(blob: ByteArray): ByteArray {
            require(blob.size > MAGIC_DEK.size + 12 + 16) { "Encrypted photo is truncated" }
            val key = when {
                blob.copyOfRange(0, MAGIC_DEK.size).contentEquals(MAGIC_DEK) -> dataKey.get(context)
                blob.copyOfRange(0, MAGIC_KEYSTORE.size).contentEquals(MAGIC_KEYSTORE) -> {
                    legacy = true
                    secretKey()
                }
                else -> error("Unsupported photo version")
            }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, MAGIC_DEK.size, 12))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, MAGIC_DEK.size + 12, blob.size - MAGIC_DEK.size - 12)
        }
        val value = AtomicStore.readWithBackup(file, ::decrypt).getOrElse {
            throw IllegalStateException("Photo could not be decrypted", it)
        }.value
        if (legacy) {
            // Files written before envelope encryption pushed every bulk byte
            // through the Keystore; rewrite once so future reads stay fast.
            runCatching { encryptToFile(context, file, aad, value) }
        }
        return value
    }

    private fun aad(documentId: String) = "paka-photo-v1:$documentId".toByteArray(Charsets.UTF_8)

    private fun displayAad(documentId: String) = "paka-photo-display-v1:$documentId".toByteArray(Charsets.UTF_8)

    private fun secretKey(): SecretKey = KeystoreKeys.getOrCreateAes256(KEY_ALIAS)
}
