package com.paka.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private enum class BackupStep { MENU, EXPORT_PASSWORD, IMPORT_PASSWORD, CONFIRM_RESTORE }
private enum class BackupField { PASSPHRASE, REPEAT }
private enum class BackupMenuAction { EXPORT, RESTORE }

@Composable
private fun BackupBottomAction(
    text: String,
    color: Color,
    fontSize: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (enabled) tapModifier(onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BackupScreen(
    cards: List<Card>,
    accounts: List<OtpAccount>,
    onRestore: suspend (BackupData) -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(BackupStep.MENU) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var unlocked by remember { mutableStateOf<BackupData?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<BackupField?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            pendingExport?.fill(0)
            pendingImport?.fill(0)
            unlocked?.clearDocuments()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        context.setPakaExternalFlowActive(false)
        val bytes = pendingExport
        if (uri == null || bytes == null) {
            bytes?.fill(0)
            pendingExport = null
        } else {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(bytes)
                            output.flush()
                        } ?: error("Document could not be opened")
                    }.isSuccess
                }
                bytes.fill(0)
                pendingExport = null
                if (saved) {
                    passphrase = ""
                    confirmation = ""
                    step = BackupStep.MENU
                    Toast.makeText(context, resources.getString(R.string.backup_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, resources.getString(R.string.backup_save_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        context.setPakaExternalFlowActive(false)
        if (uri != null) {
            scope.launch {
                busy = true
                val blob = readBackupBlobOwned(context, uri)
                busy = false
                blob.onSuccess {
                    pendingImport?.fill(0)
                    pendingImport = it
                    passphrase = ""
                    step = BackupStep.IMPORT_PASSWORD
                }.onFailure {
                    Toast.makeText(context, resources.getString(R.string.backup_read_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun resetToMenu() {
        pendingExport?.fill(0)
        pendingExport = null
        pendingImport?.fill(0)
        pendingImport = null
        unlocked?.clearDocuments()
        unlocked = null
        passphrase = ""
        confirmation = ""
        busy = false
        editingField = null
        step = BackupStep.MENU
    }

    fun returnToImportPassword() {
        unlocked?.clearDocuments()
        unlocked = null
        passphrase = ""
        confirmation = ""
        busy = false
        editingField = null
        step = BackupStep.IMPORT_PASSWORD
    }

    val goBack: () -> Unit = {
        if (!busy) {
            when (step) {
                BackupStep.MENU -> onBack()
                BackupStep.CONFIRM_RESTORE -> returnToImportPassword()
                else -> resetToMenu()
            }
        }
    }
    // Always consume system Back on this route. While work owns plaintext,
    // goBack deliberately does nothing instead of falling through to Activity.
    BackHandler { goBack() }

    editingField?.let { field ->
        TextEntryScreen(
            title = if (field == BackupField.PASSPHRASE) {
                stringResource(R.string.backup_passphrase_field)
            } else {
                stringResource(R.string.backup_repeat_field)
            },
            initial = if (field == BackupField.PASSPHRASE) passphrase else confirmation,
            onSave = { value ->
                if (field == BackupField.PASSPHRASE) passphrase = value else confirmation = value
                editingField = null
            },
            onBack = { editingField = null },
            visualTransformation = PasswordVisualTransformation(),
            allowBlank = true,
            trimOnSave = false,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Palette.background).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(
            when (step) {
                BackupStep.MENU -> stringResource(R.string.backup_title)
                BackupStep.EXPORT_PASSWORD -> stringResource(R.string.backup_export_title)
                BackupStep.IMPORT_PASSWORD -> stringResource(R.string.backup_unlock_title)
                BackupStep.CONFIRM_RESTORE -> stringResource(R.string.backup_restore_title)
            },
            goBack,
            backEnabled = !busy,
        )

        when (step) {
            BackupStep.MENU -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    PagedList(listOf(BackupMenuAction.EXPORT, BackupMenuAction.RESTORE)) { action ->
                        val label = when (action) {
                            BackupMenuAction.EXPORT -> stringResource(R.string.backup_encrypted_export)
                            BackupMenuAction.RESTORE -> stringResource(R.string.backup_restore_backup)
                        }
                        Box(
                            modifier = Modifier.fillMaxSize().then(
                                tapModifier {
                                    if (action == BackupMenuAction.EXPORT) {
                                        step = BackupStep.EXPORT_PASSWORD
                                    } else {
                                        context.setPakaExternalFlowActive(true)
                                        importLauncher.launch(arrayOf("application/octet-stream", "application/*"))
                                    }
                                },
                            ),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                label,
                                color = Palette.foreground,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.backup_offline_description),
                        color = Palette.dim,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.align(Alignment.BottomStart).padding(end = 14.dp, bottom = 20.dp),
                    )
                }
            }

            BackupStep.EXPORT_PASSWORD -> {
                val canExport = passphrase.length >= BackupCrypto.MIN_NEW_PASSPHRASE_LENGTH &&
                    passphrase == confirmation && !busy
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 28.dp).hardSnapVerticalScroll(rememberHapticScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.backup_new_passphrase_instructions,
                            BackupCrypto.MIN_NEW_PASSPHRASE_LENGTH,
                        ),
                        color = Palette.dim,
                        fontSize = 16.sp,
                    )
                    if (cards.any { it.content is PassContent.Photos }) {
                        Text(
                            stringResource(R.string.backup_photo_compatibility),
                            color = Palette.dim,
                            fontSize = 16.sp,
                        )
                    }
                    ManualEntryRow(
                        stringResource(R.string.backup_passphrase_field),
                        "•".repeat(passphrase.length),
                        stringResource(R.string.backup_minimum_characters, BackupCrypto.MIN_NEW_PASSPHRASE_LENGTH),
                    ) { editingField = BackupField.PASSPHRASE }
                    ManualEntryRow(
                        stringResource(R.string.backup_repeat_field),
                        "•".repeat(confirmation.length),
                        stringResource(R.string.backup_repeat_passphrase),
                    ) {
                        editingField = BackupField.REPEAT
                    }
                    if (confirmation.isNotEmpty() && passphrase != confirmation) {
                        Text(
                            stringResource(R.string.backup_passphrases_do_not_match),
                            color = Palette.dim,
                            fontSize = 14.sp,
                        )
                    }
                }
                BackupBottomAction(
                    text = if (busy) {
                        stringResource(R.string.backup_encrypting)
                    } else {
                        stringResource(R.string.backup_save_encrypted)
                    },
                    color = if (canExport) Palette.foreground else Palette.dim,
                    fontSize = 18,
                    enabled = canExport,
                ) {
                    val password = passphrase.toCharArray()
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val documents = linkedMapOf<String, ByteArray>()
                            val photos = linkedMapOf<String, ByteArray>()
                            try {
                                runCatching {
                                    cards.mapNotNull { it.pdfContent?.documentId }.distinct().forEach { id ->
                                        documents[id] = PdfStore.readPlaintext(context, id)
                                    }
                                    cards.photoDocumentIds().forEach { id ->
                                        photos[id] = PhotoStore.readPlaintext(context, id)
                                    }
                                    BackupStore.encrypt(cards, accounts, password, documents, photos)
                                }
                            } finally {
                                documents.values.forEach { it.fill(0) }
                                photos.values.forEach { it.fill(0) }
                                password.fill('\u0000')
                            }
                        }
                        busy = false
                        result.onSuccess { bytes ->
                            pendingExport = bytes
                            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            context.setPakaExternalFlowActive(true)
                            exportLauncher.launch(resources.getString(R.string.backup_export_filename, stamp))
                        }.onFailure { error ->
                            val message = if (error.message?.contains("too large", ignoreCase = true) == true) {
                                resources.getString(R.string.backup_too_large)
                            } else {
                                resources.getString(R.string.backup_encrypt_failed)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            BackupStep.IMPORT_PASSWORD -> {
                val canUnlock = passphrase.length >= 8 && pendingImport != null && !busy
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(stringResource(R.string.backup_import_instructions), color = Palette.dim, fontSize = 16.sp)
                    ManualEntryRow(
                        stringResource(R.string.backup_passphrase_field),
                        "•".repeat(passphrase.length),
                        stringResource(R.string.backup_passphrase_placeholder),
                    ) {
                        editingField = BackupField.PASSPHRASE
                    }
                }
                BackupBottomAction(
                    text = if (busy) {
                        stringResource(R.string.backup_unlocking)
                    } else {
                        stringResource(R.string.backup_unlock_action)
                    },
                    color = if (canUnlock) Palette.foreground else Palette.dim,
                    fontSize = 18,
                    enabled = canUnlock,
                ) {
                    val blob = checkNotNull(pendingImport)
                    val password = passphrase.toCharArray()
                    busy = true
                    scope.launch {
                        val result = decryptBackupOwned(blob, password)
                        busy = false
                        result.onSuccess { data ->
                            // Retain the encrypted blob so Back from confirmation
                            // can return one level to unlock without retaining
                            // decrypted document/photo buffers.
                            passphrase = ""
                            unlocked = data
                            step = BackupStep.CONFIRM_RESTORE
                        }.onFailure {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.backup_invalid_passphrase),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }

            BackupStep.CONFIRM_RESTORE -> {
                val data = unlocked
                val pdfUnsupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                    data?.cards?.any { it.content is PassContent.Pdf } == true
                val canRestore = data != null && !busy && !pdfUnsupported
                val passCount = data?.cards?.size ?: 0
                val codeCount = data?.accounts?.size ?: 0
                val skipped = data?.skippedPasses ?: 0
                val restoreCounts = stringResource(
                    R.string.backup_restore_counts,
                    pluralStringResource(R.plurals.backup_pass_count, passCount, passCount),
                    pluralStringResource(R.plurals.backup_code_count, codeCount, codeCount),
                )
                val skippedDescription = if (skipped > 0) {
                    pluralStringResource(R.plurals.backup_skipped_passes, skipped, skipped)
                } else {
                    null
                }
                val currentDataWarning = stringResource(R.string.backup_current_data_replaced)
                val pdfRequirement = stringResource(R.string.backup_pdf_android_requirement)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ScrollList(topPadding = 24.dp, spacing = 24.dp) {
                            Text(
                                stringResource(R.string.backup_replace_current_data),
                                color = Palette.foreground,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Normal,
                            )
                            Text(
                                buildString {
                                    append(restoreCounts)
                                    skippedDescription?.let { append("\n$it") }
                                    append("\n$currentDataWarning")
                                    if (pdfUnsupported) {
                                        append("\n$pdfRequirement")
                                    }
                                },
                                color = Palette.dim,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                    BackupBottomAction(
                        text = if (busy) {
                            stringResource(R.string.backup_restoring)
                        } else {
                            stringResource(R.string.backup_restore_action)
                        },
                        color = if (canRestore) Palette.foreground else Palette.dim,
                        fontSize = 24,
                        enabled = canRestore,
                    ) {
                        val ownedData = checkNotNull(data)
                        // Ownership of decrypted document/photo arrays transfers
                        // to the process-scoped coordinator. Clearing Back/cancel
                        // state can no longer wipe buffers during the transaction.
                        unlocked = null
                        busy = true
                        context.setPakaCriticalFlowActive(true)
                        val started = AtomicBoolean(false)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            started.set(true)
                            try {
                                val restored = onRestore(ownedData)
                                if (restored) {
                                    Toast.makeText(
                                        context,
                                        resources.getString(R.string.backup_restored),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    resetToMenu()
                                    onBack()
                                } else {
                                    resetToMenu()
                                }
                            } finally {
                                busy = false
                                context.setPakaCriticalFlowActive(false)
                            }
                        }
                        if (!started.get()) {
                            ownedData.clearDocuments()
                            context.setPakaCriticalFlowActive(false)
                        }
                    }
                    BackupBottomAction(
                        text = stringResource(R.string.backup_cancel_restore_action),
                        color = Palette.dim,
                        fontSize = 24,
                        enabled = !busy,
                    ) {
                        resetToMenu()
                    }
                }
            }
        }
    }
}

private fun readBackupBlob(context: Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("Document could not be opened")
    return input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            require(total <= BackupStore.MAX_BACKUP_BYTES) { "Backup is too large" }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

/** Keeps ownership of a completed read until the calling coroutine accepts it. */
private suspend fun readBackupBlobOwned(context: Context, uri: Uri): Result<ByteArray> {
    val outcome = AtomicReference<Result<ByteArray>?>(null)
    return try {
        withContext(NonCancellable + Dispatchers.IO) {
            outcome.set(runCatching { readBackupBlob(context, uri) })
        }
        currentCoroutineContext().ensureActive()
        outcome.getAndSet(null) ?: Result.failure(IllegalStateException("Backup read did not finish"))
    } finally {
        outcome.getAndSet(null)?.getOrNull()?.fill(0)
    }
}

/** Decryption is not cancellable; clear any plaintext result that cannot be transferred to UI state. */
private suspend fun decryptBackupOwned(blob: ByteArray, password: CharArray): Result<BackupData> {
    val input = blob.copyOf()
    val outcome = AtomicReference<Result<BackupData>?>(null)
    return try {
        withContext(NonCancellable + Dispatchers.Default) {
            try {
                outcome.set(runCatching { BackupStore.decrypt(input, password) })
            } finally {
                input.fill(0)
                password.fill('\u0000')
            }
        }
        currentCoroutineContext().ensureActive()
        outcome.getAndSet(null) ?: Result.failure(IllegalStateException("Backup unlock did not finish"))
    } finally {
        input.fill(0)
        password.fill('\u0000')
        outcome.getAndSet(null)?.getOrNull()?.clearDocuments()
    }
}
