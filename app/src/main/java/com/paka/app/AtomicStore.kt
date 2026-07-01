package com.paka.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class LoadOutcome<T>(
    val value: T,
    val warning: String? = null,
    val writable: Boolean = true,
)

/** Crash-safe file replacement with one previous-version backup. */
internal object AtomicStore {
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L

    fun backupFile(file: File): File = File(file.parentFile, "${file.name}.bak")

    fun write(file: File, bytes: ByteArray): Result<Unit> = runCatching {
        require(bytes.size <= MAX_FILE_BYTES) { "Store is unexpectedly large" }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        val backup = backupFile(file)

        temp.outputStream().use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }

        if (file.exists()) {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    fun readBytes(file: File): ByteArray {
        require(file.length() <= MAX_FILE_BYTES) { "Store is unexpectedly large" }
        return file.readBytes()
    }
}
