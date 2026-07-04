package com.paka.app

import java.io.File
import java.io.FileNotFoundException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal data class LoadOutcome<T>(
    val value: T,
    val warning: String? = null,
    val writable: Boolean = true,
)

internal data class RecoveredValue<T>(
    val value: T,
    val fromBackup: Boolean,
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
            FileChannel.open(backup.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        }

        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        syncDirectory(file.parentFile)
    }

    fun readBytes(file: File): ByteArray {
        require(file.length() <= MAX_FILE_BYTES) { "Store is unexpectedly large" }
        return file.readBytes()
    }

    fun <T> readWithBackup(file: File, decode: (ByteArray) -> T): Result<RecoveredValue<T>> {
        val primary = if (file.exists()) runCatching { decode(readBytes(file)) } else null
        primary?.getOrNull()?.let { return Result.success(RecoveredValue(it, fromBackup = false)) }

        val backup = backupFile(file)
        val recovered = if (backup.exists()) runCatching { decode(readBytes(backup)) } else null
        recovered?.getOrNull()?.let { return Result.success(RecoveredValue(it, fromBackup = true)) }

        return Result.failure(
            recovered?.exceptionOrNull()
                ?: primary?.exceptionOrNull()
                ?: FileNotFoundException("Neither ${file.name} nor its backup exists"),
        )
    }

    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        // Android/Linux supports fsync on a directory file descriptor. Keep the
        // write successful on unusual filesystems that reject opening one.
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
