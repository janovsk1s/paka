package com.paka.app

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal enum class StoreWriteStatus { SAVED, SUPERSEDED, FAILED }
internal enum class RestoreOutcome { SUCCESS, FAILED_ROLLED_BACK, FAILED_PARTIAL }

/**
 * Process-scoped owner for ordered encrypted writes.
 *
 * Writes must outlive an Activity/Compose teardown. Callers may stop observing a
 * result, but this scope continues draining the queues until the process exits.
 */
internal object StoreWriteCoordinator {
    private sealed interface CardTask {
        data class Write(
            val context: Context,
            val value: List<Card>,
            val deletePdfIds: Set<String>,
            val deletePhotoIds: Set<String>,
            val generation: Long,
            val completion: CompletableDeferred<StoreWriteStatus>,
        ) : CardTask

        data class Barrier(val completion: CompletableDeferred<Unit>) : CardTask
    }

    private sealed interface CodeTask {
        data class Write(
            val context: Context,
            val value: List<OtpAccount>,
            val generation: Long,
            val completion: CompletableDeferred<StoreWriteStatus>,
        ) : CodeTask

        data class Barrier(val completion: CompletableDeferred<Unit>) : CodeTask
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cardQueue = Channel<CardTask>(Channel.UNLIMITED)
    private val codeQueue = Channel<CodeTask>(Channel.UNLIMITED)
    private val cardMutex = Mutex()
    private val codeMutex = Mutex()
    private val cardGeneration = AtomicLong(0L)
    private val codeGeneration = AtomicLong(0L)
    @Volatile private var activeRestore: Deferred<RestoreOutcome>? = null

    init {
        scope.launch {
            for (task in cardQueue) when (task) {
                is CardTask.Write -> processCardWrite(task)
                is CardTask.Barrier -> task.completion.complete(Unit)
            }
        }
        scope.launch {
            for (task in codeQueue) when (task) {
                is CodeTask.Write -> processCodeWrite(task)
                is CodeTask.Barrier -> task.completion.complete(Unit)
            }
        }
    }

    fun saveCards(
        context: Context,
        value: List<Card>,
        deletePdfIds: Set<String> = emptySet(),
        deletePhotoIds: Set<String> = emptySet(),
    ): Deferred<StoreWriteStatus>? = synchronized(this) {
        if (activeRestore?.isActive == true) return@synchronized null
        val completion = CompletableDeferred<StoreWriteStatus>()
        val task = CardTask.Write(
            context = context.applicationContext,
            value = value,
            deletePdfIds = deletePdfIds,
            deletePhotoIds = deletePhotoIds,
            generation = cardGeneration.get(),
            completion = completion,
        )
        completion.takeIf { cardQueue.trySend(task).isSuccess }
    }

    fun saveCodes(context: Context, value: List<OtpAccount>): Deferred<StoreWriteStatus>? = synchronized(this) {
        if (activeRestore?.isActive == true) return@synchronized null
        val completion = CompletableDeferred<StoreWriteStatus>()
        val task = CodeTask.Write(
            context = context.applicationContext,
            value = value,
            generation = codeGeneration.get(),
            completion = completion,
        )
        completion.takeIf { codeQueue.trySend(task).isSuccess }
    }

    fun isRestoreActive(): Boolean = synchronized(this) { activeRestore?.isActive == true }

    /**
     * Process-owned cleanup for imports abandoned as their Compose route closes.
     * Recheck committed references under the card mutex so delayed cleanup can
     * never remove content that was saved or restored in the meantime.
     */
    fun deleteUnreferencedImports(
        context: Context,
        pdfIds: Set<String> = emptySet(),
        photoIds: Set<String> = emptySet(),
    ): Deferred<Unit>? {
        if (pdfIds.isEmpty() && photoIds.isEmpty()) return null
        val appContext = context.applicationContext
        return scope.async {
            // A duplicate candidate can refer to a card whose optimistic save
            // was already queued but has not acquired cardMutex yet. Put a
            // barrier behind every earlier card write before checking durable
            // references, otherwise cleanup can race that save and remove a
            // document it is about to commit.
            val queuedWritesDone = CompletableDeferred<Unit>()
            if (cardQueue.trySend(CardTask.Barrier(queuedWritesDone)).isFailure) return@async
            queuedWritesDone.await()
            cardMutex.withLock {
                val loaded = CardStore.load(appContext)
                if (!loaded.writable) return@withLock
                val referencedPdfs = loaded.value.mapNotNull { it.pdfContent?.documentId }.toSet()
                val referencedPhotos = loaded.value.photoDocumentIds()
                (pdfIds - referencedPdfs).forEach { PdfStore.delete(appContext, it) }
                (photoIds - referencedPhotos).forEach { PhotoStore.delete(appContext, it) }
            }
        }
    }

    /** Wait until every write queued before this call has reached stable storage. */
    suspend fun awaitPendingWrites() = coroutineScope {
        activeRestore?.await()
        val cardsDone = CompletableDeferred<Unit>()
        val codesDone = CompletableDeferred<Unit>()
        check(cardQueue.trySend(CardTask.Barrier(cardsDone)).isSuccess)
        check(codeQueue.trySend(CodeTask.Barrier(codesDone)).isSuccess)
        cardsDone.await()
        codesDone.await()
        activeRestore?.await()
    }

    /** Resolves a process-death restore marker before startup reads any store. */
    suspend fun recoverInterruptedRestore(context: Context): RestoreRecoveryOutcome =
        codeMutex.withLock {
            cardMutex.withLock { RestoreJournal.recover(context.applicationContext) }
        }

    /** Restore also outlives its calling Activity once it has begun. */
    fun restore(
        context: Context,
        restored: BackupData,
    ): Deferred<RestoreOutcome> {
        return synchronized(this) {
            check(activeRestore?.isActive != true) { "A restore is already running" }
            val appContext = context.applicationContext
            cardGeneration.incrementAndGet()
            codeGeneration.incrementAndGet()
            scope.async {
                codeMutex.withLock {
                    cardMutex.withLock {
                        performRestore(appContext, restored)
                    }
                }
            }.also { restoreJob ->
                activeRestore = restoreJob
                restoreJob.invokeOnCompletion {
                    synchronized(this) {
                        if (activeRestore === restoreJob) activeRestore = null
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun performRestore(
        context: Context,
        restored: BackupData,
    ): RestoreOutcome {
        var journal: RestoreJournalState? = null
        val result = runCatching {
            // Queued optimistic UI writes may have been superseded when restore
            // advanced the generations. Snapshot and journal the committed
            // stores under both mutexes instead of trusting caller-held state.
            val currentCards = CardStore.load(context).also { check(it.writable) }.value
            val currentCodes = SecureStore.loadAccounts(context).also { check(it.writable) }.value
            val restoredIds = restored.cards.mapNotNull { it.pdfContent?.documentId }.toSet()
            val restoredPhotoIds = restored.cards.photoDocumentIds()
            require(restored.documents.keys == restoredIds) { "Restore is missing PDF data" }
            require(restored.photos.keys == restoredPhotoIds) { "Restore is missing photo data" }
            val pdfPageCounts = restored.cards.mapNotNull { it.pdfContent }.groupBy(
                keySelector = { it.documentId },
                valueTransform = { it.pageCount },
            )
            restored.documents.forEach { (id, bytes) ->
                val counts = pdfPageCounts.getValue(id).toSet()
                require(counts.size == 1) { "PDF page count metadata is inconsistent" }
                PdfStore.validateRestore(bytes, id, counts.single())
            }
            val photoPages = restored.cards
                .flatMap { (it.content as? PassContent.Photos)?.pages.orEmpty() }
                .groupBy { it.documentId }
            restored.photos.forEach { (id, bytes) ->
                require(photoDocumentId(bytes) == id) { "Photo identifier does not match its content" }
                val dimensions = PhotoStore.inspect(bytes)
                require(
                    photoPages.getValue(id).all {
                        it.width == dimensions.first && it.height == dimensions.second
                    },
                ) {
                    "Photo dimensions do not match the backup"
                }
            }

            journal = RestoreJournal.begin(context, currentCards, currentCodes.size, restored.cards).getOrThrow()
            restored.documents.forEach { (id, bytes) ->
                PdfStore.writePlaintext(context, id, bytes)
            }
            restored.photos.forEach { (id, bytes) ->
                PhotoStore.writePlaintext(context, id, bytes)
            }
            SecureStore.saveAccounts(context, restored.accounts).getOrThrow()
            CardStore.save(context, restored.cards).getOrThrow()
            val committed = RestoreJournal.markCommitted(context, checkNotNull(journal)).getOrThrow()
            check(RestoreJournal.finishCommit(context, committed)) { "Restore cleanup could not be completed" }
            RestoreOutcome.SUCCESS
        }
        return try {
            result.getOrElse {
                // begin() can fail after durably writing PREPARED but before
                // returning its state. Always inspect disk, not just the local
                // assignment, before allowing later edits.
                when (RestoreJournal.recover(context)) {
                    RestoreRecoveryOutcome.ROLLED_BACK, RestoreRecoveryOutcome.NONE ->
                        RestoreOutcome.FAILED_ROLLED_BACK
                    RestoreRecoveryOutcome.COMMITTED ->
                        if (journal != null) RestoreOutcome.SUCCESS else RestoreOutcome.FAILED_ROLLED_BACK
                    RestoreRecoveryOutcome.FAILED -> RestoreOutcome.FAILED_PARTIAL
                }
            }
        } finally {
            restored.clearDocuments()
        }
    }

    private suspend fun processCardWrite(task: CardTask.Write) {
        if (task.generation != cardGeneration.get()) {
            task.completion.complete(StoreWriteStatus.SUPERSEDED)
            return
        }
        val result = cardMutex.withLock {
            if (task.generation != cardGeneration.get()) {
                null
            } else {
                // Deletions stay under the mutex: a restore that begins after
                // this save cannot acquire the stores until they finish, so it
                // can never re-create a document these deletions then remove.
                CardStore.save(task.context, task.value).also { saved ->
                    if (saved.isSuccess) {
                        task.deletePdfIds.forEach { PdfStore.delete(task.context, it) }
                        task.deletePhotoIds.forEach { PhotoStore.delete(task.context, it) }
                    }
                }
            }
        }
        val status = when {
            task.generation != cardGeneration.get() || result == null -> StoreWriteStatus.SUPERSEDED
            result.isSuccess -> StoreWriteStatus.SAVED
            else -> {
                cardGeneration.compareAndSet(task.generation, task.generation + 1)
                StoreWriteStatus.FAILED
            }
        }
        task.completion.complete(status)
    }

    private suspend fun processCodeWrite(task: CodeTask.Write) {
        if (task.generation != codeGeneration.get()) {
            task.completion.complete(StoreWriteStatus.SUPERSEDED)
            return
        }
        val result = codeMutex.withLock {
            if (task.generation != codeGeneration.get()) null
            else SecureStore.saveAccounts(task.context, task.value)
        }
        val status = when {
            task.generation != codeGeneration.get() || result == null -> StoreWriteStatus.SUPERSEDED
            result.isSuccess -> StoreWriteStatus.SAVED
            else -> {
                codeGeneration.compareAndSet(task.generation, task.generation + 1)
                StoreWriteStatus.FAILED
            }
        }
        task.completion.complete(status)
    }
}
