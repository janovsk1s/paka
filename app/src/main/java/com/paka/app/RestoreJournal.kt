package com.paka.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class RestoreRecoveryOutcome { NONE, ROLLED_BACK, COMMITTED, FAILED }

internal enum class RestoreJournalPhase { PREPARED, ROLLED_BACK, COMMITTED }

@Suppress("LongParameterList")
internal data class RestoreJournalState(
    val phase: RestoreJournalPhase,
    val cardsExisted: Boolean,
    val codesExisted: Boolean,
    val oldPdfIds: Set<String>,
    val restoredPdfIds: Set<String>,
    val oldPhotoIds: Set<String>,
    val restoredPhotoIds: Set<String>,
)

/**
 * Durable commit marker for a restore spanning the pass, 2FA, PDF, and photo
 * stores. Only encrypted store snapshots and content identifiers are persisted;
 * portable-backup plaintext remains in RAM and is owned by the coordinator.
 */
@Suppress("TooManyFunctions")
internal object RestoreJournal {
    private const val SCHEMA = 1
    private const val JOURNAL_FILE = "restore.journal"
    private const val CARD_SNAPSHOT_FILE = "restore.cards.enc"
    private const val CODE_SNAPSHOT_FILE = "restore.otp.enc"
    private const val MAX_IDS = 10_000

    fun begin(
        context: Context,
        oldCards: List<Card>,
        oldCodeCount: Int,
        restoredCards: List<Card>,
    ): Result<RestoreJournalState> = runCatching {
        check(!hasJournal(context)) { "An interrupted restore must be recovered first" }
        clearStaleSnapshots(context)

        val cardsExisted = snapshotStore(CardStore.storageFile(context), cardSnapshot(context))
        val codesExisted = snapshotStore(SecureStore.storageFile(context), codeSnapshot(context))
        check(cardsExisted || oldCards.isEmpty()) { "The current pass store is missing" }
        check(codesExisted || oldCodeCount == 0) { "The current 2FA store is missing" }

        val state = RestoreJournalState(
            phase = RestoreJournalPhase.PREPARED,
            cardsExisted = cardsExisted,
            codesExisted = codesExisted,
            oldPdfIds = oldCards.mapNotNull { it.pdfContent?.documentId }.toSet(),
            restoredPdfIds = restoredCards.mapNotNull { it.pdfContent?.documentId }.toSet(),
            oldPhotoIds = oldCards.photoDocumentIds(),
            restoredPhotoIds = restoredCards.photoDocumentIds(),
        )
        writeState(context, state)
        state
    }.onFailure {
        if (!hasJournal(context)) clearStaleSnapshots(context)
    }

    fun markCommitted(context: Context, prepared: RestoreJournalState): Result<RestoreJournalState> = runCatching {
        check(prepared.phase == RestoreJournalPhase.PREPARED)
        prepared.copy(phase = RestoreJournalPhase.COMMITTED).also { writeTerminalState(context, it) }
    }

    /** Completes or rolls back work left by process death before stores load. */
    fun recover(context: Context): RestoreRecoveryOutcome {
        if (!hasJournal(context)) {
            clearStaleSnapshots(context)
            return RestoreRecoveryOutcome.NONE
        }
        val state = readState(context).getOrElse { return RestoreRecoveryOutcome.FAILED }
        return when (state.phase) {
            RestoreJournalPhase.PREPARED -> {
                if (rollback(context, state)) RestoreRecoveryOutcome.ROLLED_BACK else RestoreRecoveryOutcome.FAILED
            }
            RestoreJournalPhase.ROLLED_BACK -> {
                if (finishRollback(context, state)) {
                    RestoreRecoveryOutcome.ROLLED_BACK
                } else {
                    RestoreRecoveryOutcome.FAILED
                }
            }
            RestoreJournalPhase.COMMITTED -> {
                if (finishCommit(context, state)) RestoreRecoveryOutcome.COMMITTED else RestoreRecoveryOutcome.FAILED
            }
        }
    }

    fun rollback(context: Context, state: RestoreJournalState): Boolean {
        val restored = state.phase == RestoreJournalPhase.PREPARED &&
            restoreStore(CardStore.storageFile(context), cardSnapshot(context), state.cardsExisted) &&
            restoreStore(SecureStore.storageFile(context), codeSnapshot(context), state.codesExisted) &&
            (state.restoredPdfIds - state.oldPdfIds).all { PdfStore.delete(context, it) } &&
            (state.restoredPhotoIds - state.oldPhotoIds).all { PhotoStore.delete(context, it) }
        if (!restored) return false

        val rolledBack = state.copy(phase = RestoreJournalPhase.ROLLED_BACK)
        return finishRollback(context, rolledBack)
    }

    private fun finishRollback(context: Context, state: RestoreJournalState): Boolean =
        state.phase == RestoreJournalPhase.ROLLED_BACK &&
            runCatching { writeTerminalState(context, state) }.isSuccess &&
            clearTransaction(context)

    fun finishCommit(context: Context, state: RestoreJournalState): Boolean =
        state.phase == RestoreJournalPhase.COMMITTED &&
            runCatching { writeTerminalState(context, state) }.isSuccess &&
            (state.oldPdfIds - state.restoredPdfIds).all { PdfStore.delete(context, it) } &&
            (state.oldPhotoIds - state.restoredPhotoIds).all { PhotoStore.delete(context, it) } &&
            clearTransaction(context)

    private fun snapshotStore(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        AtomicStore.write(destination, AtomicStore.readBytes(source)).getOrThrow()
        return true
    }

    private fun restoreStore(destination: File, snapshot: File, existed: Boolean): Boolean = runCatching {
        if (!existed) {
            check(AtomicStore.delete(destination)) { "Store could not be removed during rollback" }
        } else {
            val bytes = AtomicStore.readWithBackup(snapshot) { it }.getOrThrow().value
            // Two identical writes leave both the primary and fallback on the
            // known-good pre-restore generation, even if recovery is interrupted.
            AtomicStore.write(destination, bytes).getOrThrow()
            AtomicStore.write(destination, bytes).getOrThrow()
        }
    }.isSuccess

    private fun writeState(context: Context, state: RestoreJournalState) {
        val bytes = JSONObject()
            .put("schema", SCHEMA)
            .put("phase", state.phase.name)
            .put("cardsExisted", state.cardsExisted)
            .put("codesExisted", state.codesExisted)
            .put("oldPdfs", state.oldPdfIds.toJson())
            .put("restoredPdfs", state.restoredPdfIds.toJson())
            .put("oldPhotos", state.oldPhotoIds.toJson())
            .put("restoredPhotos", state.restoredPhotoIds.toJson())
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            AtomicStore.write(journal(context), bytes).getOrThrow()
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeTerminalState(context: Context, state: RestoreJournalState) {
        check(state.phase != RestoreJournalPhase.PREPARED)
        // Snapshot cleanup may remove rollback material while the journal is
        // retained for retry. Keep both journal generations on the terminal
        // phase so a corrupt primary cannot fall back to PREPARED afterward.
        writeState(context, state)
        writeState(context, state)
    }

    private fun readState(context: Context): Result<RestoreJournalState> = runCatching {
        AtomicStore.readWithBackup(journal(context)) { bytes ->
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("schema") == SCHEMA) { "Unsupported restore journal" }
            RestoreJournalState(
                phase = RestoreJournalPhase.valueOf(root.getString("phase")),
                cardsExisted = root.getBoolean("cardsExisted"),
                codesExisted = root.getBoolean("codesExisted"),
                oldPdfIds = root.getJSONArray("oldPdfs").ids(),
                restoredPdfIds = root.getJSONArray("restoredPdfs").ids(),
                oldPhotoIds = root.getJSONArray("oldPhotos").ids(),
                restoredPhotoIds = root.getJSONArray("restoredPhotos").ids(),
            )
        }.getOrThrow().value
    }

    private fun Set<String>.toJson() = JSONArray().also { array -> sorted().forEach { array.put(it) } }

    private fun JSONArray.ids(): Set<String> {
        require(length() <= MAX_IDS) { "Restore journal has too many identifiers" }
        return (0 until length()).mapTo(linkedSetOf()) { index ->
            getString(index).also { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid restore identifier" } }
        }
    }

    private fun hasJournal(context: Context): Boolean =
        journal(context).exists() || AtomicStore.backupFile(journal(context)).exists()

    private fun clearTransaction(context: Context): Boolean {
        val snapshotsCleared = listOf(cardSnapshot(context), codeSnapshot(context))
            .map(AtomicStore::delete)
            .all { it }
        return snapshotsCleared && AtomicStore.delete(journal(context))
    }

    private fun clearStaleSnapshots(context: Context) {
        AtomicStore.delete(cardSnapshot(context))
        AtomicStore.delete(codeSnapshot(context))
    }

    private fun journal(context: Context) = File(context.filesDir, JOURNAL_FILE)

    private fun cardSnapshot(context: Context) = File(context.filesDir, CARD_SNAPSHOT_FILE)

    private fun codeSnapshot(context: Context) = File(context.filesDir, CODE_SNAPSHOT_FILE)
}
