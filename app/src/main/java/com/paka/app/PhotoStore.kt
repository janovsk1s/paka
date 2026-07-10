package com.paka.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    private const val MAX_PIXELS = 48_000_000L
    private const val DIRECTORY = "photos"
    private const val SUFFIX = ".image.enc"
    private const val DISPLAY_SUFFIX = ".display.enc"
    private const val DISPLAY_MAX_DIMENSION = 2_400
    private const val DISPLAY_JPEG_QUALITY = 90
    private const val KEY_ALIAS = "paka_photo_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    // Version 1 encrypted bulk bytes directly with the Keystore key; version 2
    // uses the in-process data key. Both are 4 bytes so offsets are identical.
    private val MAGIC_KEYSTORE = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 1)
    private val MAGIC_DEK = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 2)
    private val DEK_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'D'.code.toByte(), 1)
    private val DEK_AAD = "paka-photo-dek-v1".toByteArray(Charsets.UTF_8)
    private const val DEK_FILE = "photo.dek"

    @Volatile
    private var cachedDataKey: SecretKey? = null

    private val documentLocks = ConcurrentHashMap<String, Any>()

    // Serialises fallback display-copy creation and decryption per photo.
    private inline fun <T> withDocumentLock(documentId: String, block: () -> T): T =
        synchronized(documentLocks.getOrPut(documentId) { Any() }) { block() }

    fun import(context: Context, uri: Uri): Result<PhotoImport> = runCatching {
        val bytes = readImportBytes(context, uri).getOrThrow()
        try {
            importBytes(context, bytes).getOrThrow()
        } finally {
            bytes.fill(0)
        }
    }

    /** Reads and validates an import candidate without storing it. The caller zeroes the returned bytes. */
    fun readImportBytes(context: Context, uri: Uri): Result<ByteArray> = runCatching {
        readUri(context, uri).also { inspect(it) }
    }

    /** Ingests photo bytes already in memory (an in-app capture). The caller zeroes them. */
    fun importBytes(context: Context, bytes: ByteArray): Result<PhotoImport> = runCatching {
        val dimensions = inspect(bytes)
        val documentId = photoDocumentId(bytes)
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
                    .second
            } finally {
                bytes.fill(0)
            }
        }
    }

    // Compatibility hook for MainActivity. Bitmap ownership now lives in the
    // active viewer/stack, which clears itself on background and disposal.
    fun trimMemory() = Unit

    fun delete(context: Context, documentId: String): Boolean =
        listOf(documentFile(context, documentId), displayFile(context, documentId))
            .map(AtomicStore::delete)
            .all { it }

    fun deleteOrphans(context: Context, referencedIds: Set<String>) {
        File(context.filesDir, DIRECTORY).listFiles()?.forEach { file ->
            val name = file.name.removeSuffix(".bak").removeSuffix(".corrupt")
            val documentId = when {
                name.endsWith(DISPLAY_SUFFIX) -> name.removeSuffix(DISPLAY_SUFFIX)
                name.endsWith(SUFFIX) -> name.removeSuffix(SUFFIX)
                else -> return@forEach
            }
            if (documentId !in referencedIds) {
                if (documentId.matches(Regex("[0-9a-f]{64}"))) delete(context, documentId) else file.delete()
            }
        }
    }

    fun readPlaintext(context: Context, documentId: String): ByteArray =
        decryptFile(context, documentFile(context, documentId), aad(documentId))

    fun writePlaintext(context: Context, documentId: String, bytes: ByteArray) {
        require(bytes.size <= MAX_PHOTO_BYTES) { "Photo is too large" }
        require(photoDocumentId(bytes) == documentId) { "Photo identifier does not match its content" }
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

    // Exposes the inherited buffer so compressed plaintext can be zeroed after
    // the caller takes its copy.
    private class ZeroableOutputStream : ByteArrayOutputStream() {
        fun zero() = buf.fill(0)
    }

    private fun buildDisplayBytes(original: ByteArray): ByteArray {
        // BitmapFactory does not apply EXIF on API 26/27. Share review's
        // orientation-aware bounded path so mirrored and rotated originals are
        // displayed consistently without rewriting the encrypted original.
        val bitmap = CapturedPhoto.decodePreview(original, DISPLAY_MAX_DIMENSION)
        return try {
            val output = ZeroableOutputStream()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, DISPLAY_JPEG_QUALITY, output)) {
                    "Photo could not be prepared for display"
                }
                output.toByteArray()
            } finally {
                output.zero()
            }
        } finally {
            bitmap.recycle()
        }
    }

    internal fun inspect(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PHOTO_BYTES) { "Photo is too large" }
        require(hasSupportedHeader(bytes)) { "The selected file is not a supported image" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image" }
        val (_, preview) = decodeBytes(bytes, 96, 96)
        preview.recycle()
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
    ): Pair<Pair<Int, Int>, Bitmap> {
        val maxWidth = targetWidth.coerceAtLeast(1)
        val maxHeight = targetHeight.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var dimensions = 0 to 0
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "Photo dimensions are unsupported" }
                require(width.toLong() * height <= MAX_PIXELS) { "Photo has too many pixels" }
                dimensions = width to height
                val sample = ceil(max(width.toDouble() / maxWidth, height.toDouble() / maxHeight))
                    .toInt().coerceAtLeast(1)
                decoder.setTargetSampleSize(sample)
                decoder.allocator =
                    if (allowHardware) ImageDecoder.ALLOCATOR_DEFAULT else ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return dimensions to bitmap
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image" }
        require(bounds.outWidth <= MAX_DIMENSION && bounds.outHeight <= MAX_DIMENSION) { "Photo dimensions are unsupported" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) { "Photo has too many pixels" }
        var sample = 1
        while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("The selected file is not a supported image")
        return (bounds.outWidth to bounds.outHeight) to bitmap
    }

    private fun readUri(context: Context, uri: Uri): ByteArray = UriBytes.read(
        context,
        uri,
        MAX_PHOTO_BYTES,
        tooLarge = "Photo is larger than 10 MB",
        unreadable = "Photo could not be opened",
    )

    private fun documentFile(context: Context, documentId: String): File {
        require(documentId.matches(Regex("[0-9a-f]{64}"))) { "Invalid photo identifier" }
        return File(File(context.filesDir, DIRECTORY), "$documentId$SUFFIX")
    }

    private fun displayFile(context: Context, documentId: String): File {
        require(documentId.matches(Regex("[0-9a-f]{64}"))) { "Invalid photo identifier" }
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
        cipher.init(Cipher.ENCRYPT_MODE, dataKey(context))
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
                blob.copyOfRange(0, MAGIC_DEK.size).contentEquals(MAGIC_DEK) -> dataKey(context)
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

    /**
     * Bulk photo bytes are encrypted in-process with a random data key that is
     * itself wrapped by the hardware-backed Keystore key. Keystore — and
     * StrongBox especially — is far too slow for megabytes of AES, while the
     * CPU's AES instructions are effectively free; the master key still never
     * leaves the hardware.
     */
    private fun dataKey(context: Context): SecretKey {
        cachedDataKey?.let { return it }
        synchronized(this) {
            cachedDataKey?.let { return it }
            val file = File(File(context.filesDir, DIRECTORY), DEK_FILE)
            val master = secretKey()
            fun unwrap(blob: ByteArray): ByteArray {
                require(blob.size > DEK_MAGIC.size + 12 + 16) { "Photo key is truncated" }
                require(blob.copyOfRange(0, DEK_MAGIC.size).contentEquals(DEK_MAGIC)) { "Unsupported photo key" }
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, blob, DEK_MAGIC.size, 12))
                cipher.updateAAD(DEK_AAD)
                return cipher.doFinal(blob, DEK_MAGIC.size + 12, blob.size - DEK_MAGIC.size - 12)
            }
            val raw = if (file.exists() || AtomicStore.backupFile(file).exists()) {
                AtomicStore.readWithBackup(file, ::unwrap).getOrElse {
                    // Regenerating here would silently orphan every envelope-
                    // encrypted photo, so fail loudly instead.
                    throw IllegalStateException("Photo key could not be unwrapped", it)
                }.value
            } else {
                ByteArray(32).also(SecureRandom()::nextBytes).also { fresh ->
                    val cipher = Cipher.getInstance(TRANSFORM)
                    cipher.init(Cipher.ENCRYPT_MODE, master)
                    cipher.updateAAD(DEK_AAD)
                    AtomicStore.write(file, DEK_MAGIC + cipher.iv + cipher.doFinal(fresh)).getOrThrow()
                }
            }
            require(raw.size == 32) { "Invalid photo key" }
            val key = SecretKeySpec(raw, "AES")
            raw.fill(0)
            cachedDataKey = key
            return key
        }
    }

    private fun aad(documentId: String) = "paka-photo-v1:$documentId".toByteArray(Charsets.UTF_8)

    private fun displayAad(documentId: String) = "paka-photo-display-v1:$documentId".toByteArray(Charsets.UTF_8)

    private fun secretKey(): SecretKey = KeystoreKeys.getOrCreateAes256(KEY_ALIAS)
}

internal fun photoDocumentId(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
