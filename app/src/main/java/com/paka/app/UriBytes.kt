package com.paka.app

import android.content.Context
import android.net.Uri
import java.io.InputStream

/**
 * Reads a document URI fully into memory for the encrypted import paths.
 * Every intermediate buffer is zeroed, including on failure, so plaintext
 * never lingers in copies the caller cannot reach.
 */
internal object UriBytes {
    fun read(context: Context, uri: Uri, maxBytes: Int, tooLarge: String, unreadable: String): ByteArray {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) require(descriptor.length <= maxBytes) { tooLarge }
        }
        val input = context.contentResolver.openInputStream(uri) ?: error(unreadable)
        return input.use { readBounded(it, maxBytes, tooLarge) }
    }

    internal fun readBounded(stream: InputStream, maxBytes: Int, tooLarge: String): ByteArray {
        var buffer = ByteArray(minOf(64 * 1024, maxBytes + 1))
        var total = 0
        try {
            while (true) {
                if (total == buffer.size) {
                    // One byte of headroom past the limit lets the size check
                    // below fire before another growth step is needed.
                    val grown = ByteArray(minOf(buffer.size * 2L, maxBytes + 1L).toInt())
                    buffer.copyInto(grown, 0, 0, total)
                    buffer.fill(0)
                    buffer = grown
                }
                val count = stream.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { tooLarge }
            }
            return if (total == buffer.size) buffer else buffer.copyOf(total).also { buffer.fill(0) }
        } catch (error: Throwable) {
            buffer.fill(0)
            throw error
        }
    }
}
