package com.paka.app

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The largest backup Paka allows (20 MB of PDFs, 32 MB of photos) must export
 * and unlock inside the app's own heap while the app itself is running.
 * Whole-file AES-GCM holds several copies at once; with the default 192 MiB
 * heap of Paka_LP3 this ran out of memory once the app held about 40 MiB.
 */
class BackupMemoryTest {
    @Test
    fun largestAllowedBackupExportsAndUnlocks() {
        // Stands in for the UI, render cache and stores that are live while a
        // backup runs; without it this process has far more room than the app.
        val runningApp = ByteArray(RUNNING_APP_MIB * MIB).also { it.fill(1) }
        val random = Random(7)
        fun bytes(header: ByteArray, size: Int) = ByteArray(size).also { random.nextBytes(it); header.copyInto(it) }
        var pdfs: Map<String, ByteArray>? = List(2) {
            bytes("%PDF-1.7\n".toByteArray(), BackupStore.MAX_PDF_TOTAL_BYTES / 2)
        }.associateBy(::documentDigest)
        var photos: Map<String, ByteArray>? = List(4) {
            bytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), BackupStore.MAX_PHOTO_TOTAL_BYTES / 4)
        }.associateBy(::documentDigest)
        val cards = pdfs.orEmpty().keys.map { Card("PDF", PassContent.Pdf(it, 1)) } +
            photos.orEmpty().keys.map { Card("Photo", PassContent.Photos(listOf(PhotoPage(it, 1_000, 1_000)))) }

        val blob = peakDuring("export") {
            BackupStore.encrypt(cards, emptyList(), PASSPHRASE.toCharArray(), pdfs.orEmpty(), photos.orEmpty())
        }
        // The export screen drops its plaintext copies once the backup is written.
        pdfs = null
        photos = null

        val unlocked = peakDuring("unlock") {
            runBlocking { decryptBackupOwned(blob, PASSPHRASE.toCharArray()) }.getOrThrow()
        }

        assertEquals(cards, unlocked.cards)
        assertEquals(2, unlocked.documents.size)
        assertEquals(4, unlocked.photos.size)
        unlocked.clearDocuments()
        check(runningApp.isNotEmpty())
    }

    /** Logs the highest heap use seen while [block] runs, for comparing memory fixes. */
    private fun <T> peakDuring(label: String, block: () -> T): T {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val peak = AtomicLong()
        val sampler = thread {
            while (!Thread.currentThread().isInterrupted) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return try {
            block()
        } finally {
            sampler.interrupt()
            sampler.join()
            Log.i("BackupMemoryTest", "$label peak heap ${peak.get() / MIB} MiB of ${runtime.maxMemory() / MIB} MiB")
        }
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        const val MIB = 1024 * 1024
        const val RUNNING_APP_MIB = 48
    }
}
