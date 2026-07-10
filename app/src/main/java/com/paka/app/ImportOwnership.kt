package com.paka.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** PdfRenderer/import work is non-cancellable; clean up a completed import until state accepts it. */
internal suspend fun importPdfOwned(context: Context, uri: Uri): Result<PdfImport> {
    val outcome = AtomicReference<Result<PdfImport>?>(null)
    return try {
        withContext(NonCancellable + Dispatchers.IO) {
            outcome.set(PdfStore.import(context, uri))
        }
        currentCoroutineContext().ensureActive()
        outcome.getAndSet(null) ?: Result.failure(IllegalStateException("PDF import did not finish"))
    } finally {
        outcome.getAndSet(null)?.getOrNull()?.takeIf(PdfImport::created)?.let { abandoned ->
            StoreWriteCoordinator.deleteUnreferencedImports(context, pdfIds = setOf(abandoned.documentId))
        }
    }
}

/** Holds every selected-photo plaintext array until the review batch accepts ownership. */
internal suspend fun readPhotoBatchOwned(context: Context, uris: List<Uri>): Result<List<ByteArray>> {
    val outcome = AtomicReference<Result<List<ByteArray>>?>(null)
    return try {
        withContext(NonCancellable + Dispatchers.IO) {
            outcome.set(
                runCatching {
                    val picked = mutableListOf<ByteArray>()
                    var completed = false
                    try {
                        uris.distinctBy(Uri::toString).take(2).forEach { uri ->
                            picked += PhotoStore.readImportBytes(context, uri).getOrThrow()
                        }
                        picked.toList().also { completed = true }
                    } finally {
                        if (!completed) picked.forEach { it.fill(0) }
                    }
                },
            )
        }
        currentCoroutineContext().ensureActive()
        outcome.getAndSet(null) ?: Result.failure(IllegalStateException("Photo import did not finish"))
    } finally {
        outcome.getAndSet(null)?.getOrNull()?.forEach { it.fill(0) }
    }
}
