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

    fun saveCards(context: Context, value: List<Card>): Deferred<StoreWriteStatus>? {
        val completion = CompletableDeferred<StoreWriteStatus>()
        val task = CardTask.Write(
            context = context.applicationContext,
            value = value,
            generation = cardGeneration.get(),
            completion = completion,
        )
        return completion.takeIf { cardQueue.trySend(task).isSuccess }
    }

    fun saveCodes(context: Context, value: List<OtpAccount>): Deferred<StoreWriteStatus>? {
        val completion = CompletableDeferred<StoreWriteStatus>()
        val task = CodeTask.Write(
            context = context.applicationContext,
            value = value,
            generation = codeGeneration.get(),
            completion = completion,
        )
        return completion.takeIf { codeQueue.trySend(task).isSuccess }
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

    /** Restore also outlives its calling Activity once it has begun. */
    fun restore(
        context: Context,
        oldCards: List<Card>,
        oldCodes: List<OtpAccount>,
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
                        val codesSaved = SecureStore.saveAccounts(appContext, restored.accounts)
                        if (codesSaved.isFailure) RestoreOutcome.FAILED_ROLLED_BACK
                        else if (CardStore.save(appContext, restored.cards).isSuccess) RestoreOutcome.SUCCESS
                        else if (SecureStore.saveAccounts(appContext, oldCodes).isSuccess) RestoreOutcome.FAILED_ROLLED_BACK
                        else RestoreOutcome.FAILED_PARTIAL
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

    private suspend fun processCardWrite(task: CardTask.Write) {
        if (task.generation != cardGeneration.get()) {
            task.completion.complete(StoreWriteStatus.SUPERSEDED)
            return
        }
        val result = cardMutex.withLock {
            if (task.generation != cardGeneration.get()) null
            else CardStore.save(task.context, task.value)
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
