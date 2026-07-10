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

    fun corruptFile(file: File): File = File(file.parentFile, "${file.name}.corrupt")

    fun write(file: File, bytes: ByteArray): Result<Unit> = writeInternal(file, bytes, rotatePrimary = true)

    /**
     * Replaces content whose identity was independently verified without
     * rotating a possibly corrupt primary over a known-good fallback.
     */
    fun replaceVerified(file: File, bytes: ByteArray): Result<Unit> =
        writeInternal(file, bytes, rotatePrimary = false)

    private fun writeInternal(file: File, bytes: ByteArray, rotatePrimary: Boolean): Result<Unit> = runCatching {
        require(bytes.size <= MAX_FILE_BYTES) { "Store is unexpectedly large" }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        val backup = backupFile(file)

        temp.outputStream().use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }

        if (file.exists() && (rotatePrimary || !backup.exists())) {
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
        // A successful replacement makes retained corrupt evidence obsolete.
        corruptFile(file).delete()
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
        val recovered = if (backup.exists()) {
            runCatching {
                val bytes = readBytes(backup)
                val value = decode(bytes)
                promoteRecoveredBackup(file, bytes)
                RecoveredValue(value, fromBackup = true)
            }
        } else {
            null
        }
        recovered?.getOrNull()?.let { return Result.success(it) }

        return Result.failure(
            recovered?.exceptionOrNull()
                ?: primary?.exceptionOrNull()
                ?: FileNotFoundException("Neither ${file.name} nor its backup exists"),
        )
    }

    /**
     * Makes the decoded backup the primary generation without rotating a
     * corrupt primary over that known-good backup. The corrupt encrypted bytes
     * are retained separately for diagnosis instead of being destroyed.
     */
    private fun promoteRecoveredBackup(file: File, backupBytes: ByteArray) {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            val corrupt = corruptFile(file)
            Files.copy(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(corrupt.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        }

        val temp = File(file.parentFile, "${file.name}.recover")
        try {
            temp.outputStream().use { output ->
                output.write(backupBytes)
                output.flush()
                output.fd.sync()
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
            }
            syncDirectory(file.parentFile)
        } finally {
            temp.delete()
        }
    }

    /** Removes an atomic file and all generations used for recovery. */
    fun delete(file: File): Boolean {
        val deleted = listOf(file, backupFile(file), corruptFile(file))
            .map { !it.exists() || it.delete() }
            .all { it }
        syncDirectory(file.parentFile)
        return deleted
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
