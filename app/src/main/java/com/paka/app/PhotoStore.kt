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
    private const val MAX_PIXELS = 48_000_000L
    private const val DIRECTORY = "photos"
    private const val SUFFIX = ".image.enc"
    private const val DISPLAY_SUFFIX = ".display.enc"
    private const val DISPLAY_MAX_DIMENSION = 2_400
    private const val DISPLAY_JPEG_QUALITY = 90
    private const val KEY_ALIAS = "paka_photo_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 1)

    fun import(context: Context, uri: Uri): Result<PhotoImport> = runCatching {
        val bytes = readUri(context, uri)
        try {
            val dimensions = inspect(bytes)
            val documentId = photoDocumentId(bytes)
            val file = documentFile(context, documentId)
            val created = !file.exists()
            encryptToFile(file, aad(documentId), bytes)
            runCatching { writeDisplayCopy(context, documentId, bytes) }
            PhotoImport(PhotoPage(documentId, dimensions.first, dimensions.second), created)
        } finally {
            bytes.fill(0)
        }
    }

    fun decode(context: Context, documentId: String, targetWidth: Int, targetHeight: Int): Bitmap {
        val bytes = displayPlaintext(context, documentId)
        return try {
            // Hardware bitmaps live in GPU memory, keeping pinch-zoom smooth.
            decodeBytes(bytes, targetWidth, targetHeight, allowHardware = true)
                .second
        } finally {
            bytes.fill(0)
        }
    }

    fun delete(context: Context, documentId: String) {
        documentFile(context, documentId).delete()
        AtomicStore.backupFile(documentFile(context, documentId)).delete()
        displayFile(context, documentId).delete()
        AtomicStore.backupFile(displayFile(context, documentId)).delete()
    }

    fun deleteOrphans(context: Context, referencedIds: Set<String>) {
        File(context.filesDir, DIRECTORY).listFiles()?.forEach { file ->
            val name = file.name.removeSuffix(".bak")
            val documentId = when {
                name.endsWith(DISPLAY_SUFFIX) -> name.removeSuffix(DISPLAY_SUFFIX)
                name.endsWith(SUFFIX) -> name.removeSuffix(SUFFIX)
                else -> return@forEach
            }
            if (documentId !in referencedIds) file.delete()
        }
    }

    fun readPlaintext(context: Context, documentId: String): ByteArray =
        decryptFile(documentFile(context, documentId), aad(documentId))

    fun writePlaintext(context: Context, documentId: String, bytes: ByteArray) {
        require(bytes.size <= MAX_PHOTO_BYTES) { "Photo is too large" }
        require(photoDocumentId(bytes) == documentId) { "Photo identifier does not match its content" }
        inspect(bytes)
        encryptToFile(documentFile(context, documentId), aad(documentId), bytes)
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
            runCatching { return decryptFile(display, displayAad(documentId)) }
        }
        val original = decryptFile(documentFile(context, documentId), aad(documentId))
        return try {
            buildDisplayBytes(original).also {
                runCatching { encryptToFile(display, displayAad(documentId), it) }
            }
        } finally {
            original.fill(0)
        }
    }

    private fun writeDisplayCopy(context: Context, documentId: String, original: ByteArray) {
        val display = buildDisplayBytes(original)
        try {
            encryptToFile(displayFile(context, documentId), displayAad(documentId), display)
        } finally {
            display.fill(0)
        }
    }

    private fun buildDisplayBytes(original: ByteArray): ByteArray {
        val bitmap = decodeBytes(original, DISPLAY_MAX_DIMENSION, DISPLAY_MAX_DIMENSION).second
        return try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, DISPLAY_JPEG_QUALITY, output)) {
                "Photo could not be prepared for display"
            }
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    internal fun inspect(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PHOTO_BYTES) { "Photo is too large" }
        require(hasSupportedHeader(bytes)) { "The selected file is not a supported image" }
        val (dimensions, preview) = decodeBytes(bytes, 96, 96)
        preview.recycle()
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

    private fun readUri(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) require(descriptor.length <= MAX_PHOTO_BYTES) { "Photo is larger than 10 MB" }
        }
        val input = context.contentResolver.openInputStream(uri) ?: error("Photo could not be opened")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_PHOTO_BYTES) { "Photo is larger than 10 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun documentFile(context: Context, documentId: String): File {
        require(documentId.matches(Regex("[0-9a-f]{64}"))) { "Invalid photo identifier" }
        return File(File(context.filesDir, DIRECTORY), "$documentId$SUFFIX")
    }

    private fun displayFile(context: Context, documentId: String): File {
        require(documentId.matches(Regex("[0-9a-f]{64}"))) { "Invalid photo identifier" }
        return File(File(context.filesDir, DIRECTORY), "$documentId$DISPLAY_SUFFIX")
    }

    private fun encryptToFile(file: File, aad: ByteArray, plaintext: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad)
        AtomicStore.write(file, MAGIC + cipher.iv + cipher.doFinal(plaintext)).getOrThrow()
    }

    private fun decryptFile(file: File, aad: ByteArray): ByteArray {
        fun decrypt(blob: ByteArray): ByteArray {
            require(blob.size > MAGIC.size + 12 + 16) { "Encrypted photo is truncated" }
            require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Unsupported photo version" }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, MAGIC.size, 12))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, MAGIC.size + 12, blob.size - MAGIC.size - 12)
        }
        return AtomicStore.readWithBackup(file, ::decrypt).getOrElse {
            throw IllegalStateException("Photo could not be decrypted", it)
        }.value
    }

    private fun aad(documentId: String) = "paka-photo-v1:$documentId".toByteArray(Charsets.UTF_8)

    private fun displayAad(documentId: String) = "paka-photo-display-v1:$documentId".toByteArray(Charsets.UTF_8)

    private fun secretKey(): SecretKey = KeystoreKeys.getOrCreateAes256(KEY_ALIAS)
}

internal fun photoDocumentId(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
