package com.paka.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PdfImport(
    val documentId: String,
    val pageCount: Int,
    val created: Boolean,
)

internal data class PdfPageBitmap(
    val bitmap: Bitmap,
    val pageWidth: Int,
    val pageHeight: Int,
)

@RequiresApi(30)
internal class PdfDocumentSession private constructor(
    private val renderer: PdfRenderer,
) : AutoCloseable {
    private val renderMutex = Mutex()
    private var rendererClosed = false // guarded by renderMutex
    val pageCount: Int = renderer.pageCount

    suspend fun renderPage(
        index: Int,
        targetWidth: Int,
        targetHeight: Int = Int.MAX_VALUE,
    ): PdfPageBitmap = renderMutex.withLock {
        check(!rendererClosed) { "PDF session is closed" }
        require(index in 0 until pageCount)
        renderer.openPage(index).use { page ->
            val maxWidth = targetWidth.coerceIn(240, 1_440)
            val maxHeight = targetHeight.coerceAtLeast(1)
            val scale = minOf(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            var completed = false
            try {
                // PDF pages conventionally sit on white paper, but many files do
                // not paint that background themselves. Without this, transparent
                // page areas disappear into Paka's black canvas.
                bitmap.eraseColor(Color.WHITE)
                page.render(
                    bitmap,
                    null,
                    Matrix().apply { setScale(scale, scale) },
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                PdfPageBitmap(bitmap, page.width, page.height).also { completed = true }
            } finally {
                if (!completed) bitmap.recycle()
            }
        }
    }

    /** Render the current transformed page directly into a screen-sized sharp layer. */
    suspend fun renderViewport(
        index: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        baseScale: Float,
        zoom: Float,
        pageLeft: Float,
        pageTop: Float,
        translationX: Float,
        translationY: Float,
    ): Bitmap = renderMutex.withLock {
        check(!rendererClosed) { "PDF session is closed" }
        require(index in 0 until pageCount)
        renderer.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
            var completed = false
            try {
                val pageScale = baseScale * zoom
                val pageX = pageLeft + translationX
                val pageY = pageTop + translationY
                Canvas(bitmap).drawRect(
                    pageX,
                    pageY,
                    pageX + page.width * pageScale,
                    pageY + page.height * pageScale,
                    Paint().apply { color = Color.WHITE },
                )
                val matrix = Matrix().apply {
                    setScale(pageScale, pageScale)
                    postTranslate(pageX, pageY)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.also { completed = true }
            } finally {
                if (!completed) bitmap.recycle()
            }
        }
    }

    // PdfRenderer is not thread-safe: closing must never run concurrently with a
    // render. Close inline when the mutex is free; otherwise hand off to a
    // coroutine that waits behind the in-flight render.
    override fun close() {
        if (renderMutex.tryLock()) {
            try {
                closeRendererLocked()
            } finally {
                renderMutex.unlock()
            }
        } else {
            closeScope.launch { renderMutex.withLock { closeRendererLocked() } }
        }
    }

    private fun closeRendererLocked() {
        if (!rendererClosed) {
            rendererClosed = true
            renderer.close()
        }
    }

    companion object {
        private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun fromBytes(bytes: ByteArray): PdfDocumentSession {
            val rawFd = Os.memfd_create("paka-pdf", 0)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val written = Os.write(rawFd, bytes, offset, bytes.size - offset)
                    check(written > 0) { "PDF memory write stalled" }
                    offset += written
                }
                Os.lseek(rawFd, 0, OsConstants.SEEK_SET)
                val parcelFd = ParcelFileDescriptor.dup(rawFd)
                return try {
                    PdfDocumentSession(PdfRenderer(parcelFd))
                } catch (error: Throwable) {
                    parcelFd.close()
                    throw error
                }
            } finally {
                Os.close(rawFd)
            }
        }
    }
}

/** Per-document Keystore encryption. Plaintext exists only in RAM and memfd. */
internal object PdfStore {
    const val MAX_PDF_BYTES = 10 * 1024 * 1024
    private const val DIRECTORY = "pdfs"
    private const val SUFFIX = ".pdf.enc"
    private const val KEY_ALIAS = "paka_pdf_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    // Version 1 encrypted bulk bytes directly with the Keystore key; version 2
    // uses the wrapped in-process data key. Both are 4 bytes so offsets match.
    private val MAGIC_KEYSTORE = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'P'.code.toByte(), 1)
    private val MAGIC_DEK = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'P'.code.toByte(), 2)
    private val dataKey = WrappedDataKey(
        directory = DIRECTORY,
        fileName = "pdf.dek",
        keyAlias = KEY_ALIAS,
        aad = "paka-pdf-dek-v1".toByteArray(Charsets.UTF_8),
    )

    @RequiresApi(30)
    suspend fun import(context: Context, uri: Uri): Result<PdfImport> = runCatching {
        val bytes = readUri(context, uri)
        try {
            require(hasPdfHeader(bytes)) { "The selected file is not a PDF" }
            val documentId = documentDigest(bytes)
            val stored = storedPlaintextBytes(File(context.filesDir, DIRECTORY), SUFFIX, documentId)
            if (stored + bytes.size > BackupStore.MAX_PDF_TOTAL_BYTES) throw BackupLimitException()
            val pageCount = PdfDocumentSession.fromBytes(bytes).use { session ->
                require(session.pageCount in 1..1_000) { "PDF has an unsupported page count" }
                val preview = session.renderPage(0, 320)
                preview.bitmap.recycle()
                session.pageCount
            }
            val file = documentFile(context, documentId)
            val created = !file.exists()
            // The content hash and render validation above establish that the
            // replacement is usable. Preserve any known-good backup if the
            // current primary is corrupt or a replacement is interrupted.
            encryptToFile(context, file, documentId, bytes)
            PdfImport(documentId, pageCount, created)
        } finally {
            bytes.fill(0)
        }
    }

    @RequiresApi(30)
    fun open(context: Context, documentId: String): PdfDocumentSession {
        val bytes = decryptFile(context, documentFile(context, documentId), documentId)
        return try {
            PdfDocumentSession.fromBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun delete(context: Context, documentId: String): Boolean =
        AtomicStore.delete(documentFile(context, documentId))

    fun deleteOrphans(context: Context, referencedIds: Set<String>) {
        val directory = File(context.filesDir, DIRECTORY)
        directory.listFiles()?.forEach { file ->
            val name = atomicBaseName(file.name)
            if (!name.endsWith(SUFFIX)) return@forEach
            val id = name.removeSuffix(SUFFIX)
            if (id !in referencedIds) {
                if (id.matches(DOCUMENT_ID_PATTERN)) AtomicStore.delete(documentFile(context, id)) else file.delete()
            }
        }
    }

    fun readPlaintext(context: Context, documentId: String): ByteArray =
        decryptFile(context, documentFile(context, documentId), documentId)

    fun writePlaintext(context: Context, documentId: String, bytes: ByteArray) {
        require(documentDigest(bytes) == documentId) { "PDF identifier does not match its content" }
        require(bytes.size <= MAX_PDF_BYTES) { "PDF is too large" }
        require(hasPdfHeader(bytes)) { "PDF header is invalid" }
        encryptToFile(context, documentFile(context, documentId), documentId, bytes)
    }

    /** Applies the same open-and-render validation used by interactive import. */
    suspend fun validateRestore(bytes: ByteArray, documentId: String, expectedPageCount: Int) {
        require(documentDigest(bytes) == documentId) { "PDF identifier does not match its content" }
        require(bytes.size <= MAX_PDF_BYTES) { "PDF is too large" }
        require(hasPdfHeader(bytes)) { "PDF header is invalid" }
        require(expectedPageCount in 1..1_000) { "PDF page count is invalid" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) error("PDF passes require Android 11 or newer")
        // PdfRenderer is a platform-native service and is not implemented by
        // the local JVM test runtime. Every real API 30+ restore still opens
        // and renders page 1 before the transaction journal is created.
        if (Build.FINGERPRINT != "robolectric") {
            validateRenderable(bytes, expectedPageCount)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun validateRenderable(bytes: ByteArray, expectedPageCount: Int) {
        PdfDocumentSession.fromBytes(bytes).use { session ->
            require(session.pageCount == expectedPageCount) {
                "PDF page count does not match the backup"
            }
            session.renderPage(0, 320).bitmap.recycle()
        }
    }

    private fun readUri(context: Context, uri: Uri): ByteArray = UriBytes.read(
        context,
        uri,
        MAX_PDF_BYTES,
        tooLarge = "PDF is larger than 10 MB",
        unreadable = "PDF could not be opened",
    )

    private fun hasPdfHeader(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 1_024)
        if (limit < 5) return false
        for (index in 0..limit - 5) {
            if (bytes[index] == '%'.code.toByte() &&
                bytes[index + 1] == 'P'.code.toByte() &&
                bytes[index + 2] == 'D'.code.toByte() &&
                bytes[index + 3] == 'F'.code.toByte() &&
                bytes[index + 4] == '-'.code.toByte()
            ) return true
        }
        return false
    }

    private fun documentFile(context: Context, documentId: String): File {
        require(documentId.matches(DOCUMENT_ID_PATTERN)) { "Invalid PDF identifier" }
        return File(File(context.filesDir, DIRECTORY), "$documentId$SUFFIX")
    }

    // Content hashes and render validation establish every replacement, so a
    // possibly corrupt primary is never rotated over a known-good backup. PDF
    // writes are rare; serialising them keeps a one-time v1 rewrite from
    // sharing an AtomicStore temporary with an import of the same document.
    @Synchronized
    private fun encryptToFile(context: Context, file: File, documentId: String, plaintext: ByteArray) {
        require(plaintext.size <= MAX_PDF_BYTES) { "PDF is too large" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey.get(context))
        cipher.updateAAD(aad(documentId))
        val encrypted = MAGIC_DEK + cipher.iv + cipher.doFinal(plaintext)
        AtomicStore.replaceVerified(file, encrypted).getOrThrow()
    }

    private fun decryptFile(context: Context, file: File, documentId: String): ByteArray {
        var legacy = false
        fun decrypt(blob: ByteArray): ByteArray {
            require(blob.size > MAGIC_DEK.size + 12 + 16) { "Encrypted PDF is truncated" }
            val key = when {
                blob.copyOfRange(0, MAGIC_DEK.size).contentEquals(MAGIC_DEK) -> dataKey.get(context)
                blob.copyOfRange(0, MAGIC_KEYSTORE.size).contentEquals(MAGIC_KEYSTORE) -> {
                    legacy = true
                    secretKey()
                }
                else -> error("Unsupported PDF version")
            }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, MAGIC_DEK.size, 12))
            cipher.updateAAD(aad(documentId))
            return cipher.doFinal(blob, MAGIC_DEK.size + 12, blob.size - MAGIC_DEK.size - 12)
        }
        val value = AtomicStore.readWithBackup(file, ::decrypt).getOrElse {
            throw IllegalStateException("PDF could not be decrypted", it)
        }.value
        if (legacy) {
            // Files written before envelope encryption pushed every bulk byte
            // through the Keystore; rewrite once so future opens stay fast.
            runCatching { encryptToFile(context, file, documentId, value) }
        }
        return value
    }

    private fun aad(documentId: String) = "paka-pdf-v1:$documentId".toByteArray(Charsets.UTF_8)

    private fun secretKey(): SecretKey = KeystoreKeys.getOrCreateAes256(KEY_ALIAS)
}
