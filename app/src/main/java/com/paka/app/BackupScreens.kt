package com.paka.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BackupStep { MENU, EXPORT_PASSWORD, IMPORT_PASSWORD, CONFIRM_RESTORE }
private enum class BackupField { PASSPHRASE, REPEAT }

@Composable
internal fun BackupScreen(
    cards: List<Card>,
    accounts: List<OtpAccount>,
    onRestore: suspend (BackupData) -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(BackupStep.MENU) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var unlocked by remember { mutableStateOf<BackupData?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<BackupField?>(null) }

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
                    Toast.makeText(context, "encrypted backup saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "backup could not be saved", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        context.setPakaExternalFlowActive(false)
        if (uri != null) {
            scope.launch {
                busy = true
                val blob = withContext(Dispatchers.IO) { runCatching { readBackupBlob(context, uri) } }
                busy = false
                blob.onSuccess {
                    pendingImport?.fill(0)
                    pendingImport = it
                    passphrase = ""
                    step = BackupStep.IMPORT_PASSWORD
                }.onFailure {
                    Toast.makeText(context, "backup could not be read", Toast.LENGTH_LONG).show()
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

    val goBack: () -> Unit = {
        if (step == BackupStep.MENU) onBack() else resetToMenu()
    }
    BackHandler { goBack() }

    editingField?.let { field ->
        TextEntryScreen(
            title = if (field == BackupField.PASSPHRASE) "passphrase" else "repeat",
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

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(
            when (step) {
                BackupStep.MENU -> "backup"
                BackupStep.EXPORT_PASSWORD -> "export"
                BackupStep.IMPORT_PASSWORD -> "unlock"
                BackupStep.CONFIRM_RESTORE -> "restore"
            },
            goBack,
        )

        when (step) {
            BackupStep.MENU -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    PagedList(listOf("encrypted export", "restore backup")) { item ->
                        Text(
                            item,
                            color = White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().then(
                                tapModifier {
                                    if (item == "encrypted export") step = BackupStep.EXPORT_PASSWORD
                                    else {
                                        context.setPakaExternalFlowActive(true)
                                        importLauncher.launch(arrayOf("application/octet-stream", "application/*"))
                                    }
                                },
                            ),
                        )
                    }
                    Text(
                        "Backups are encrypted offline and contain all passes and 2FA secrets. External file references are not included.",
                        color = Grey,
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
                        "Use at least ${BackupCrypto.MIN_NEW_PASSPHRASE_LENGTH} characters. This passphrase cannot be recovered.",
                        color = Grey,
                        fontSize = 16.sp,
                    )
                    if (cards.any { it.content is PassContent.Photos }) {
                        Text(
                            "This backup contains photo passes and can only be restored by Paka 0.14 or newer.",
                            color = Grey,
                            fontSize = 16.sp,
                        )
                    }
                    ManualEntryRow(
                        "passphrase",
                        "•".repeat(passphrase.length),
                        "${BackupCrypto.MIN_NEW_PASSPHRASE_LENGTH}+ characters",
                    ) { editingField = BackupField.PASSPHRASE }
                    ManualEntryRow("repeat", "•".repeat(confirmation.length), "repeat passphrase") {
                        editingField = BackupField.REPEAT
                    }
                    if (confirmation.isNotEmpty() && passphrase != confirmation) {
                        Text("passphrases do not match", color = Grey, fontSize = 14.sp)
                    }
                }
                Text(
                    if (busy) "encrypting…" else "save encrypted backup",
                    color = if (canExport) White else Grey,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 18.dp).then(
                        if (canExport) tapModifier {
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
                                    exportLauncher.launch("paka-$stamp.paka")
                                }.onFailure { error ->
                                    val message = error.message?.takeIf { it.contains("too large", ignoreCase = true) }
                                        ?: "backup could not be encrypted"
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        } else Modifier,
                    ),
                )
            }

            BackupStep.IMPORT_PASSWORD -> {
                val canUnlock = passphrase.length >= 8 && pendingImport != null && !busy
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text("Enter the passphrase used when this backup was created.", color = Grey, fontSize = 16.sp)
                    ManualEntryRow("passphrase", "•".repeat(passphrase.length), "backup passphrase") {
                        editingField = BackupField.PASSPHRASE
                    }
                }
                Text(
                    if (busy) "unlocking…" else "unlock backup",
                    color = if (canUnlock) White else Grey,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 18.dp).then(
                        if (canUnlock) tapModifier {
                            val blob = checkNotNull(pendingImport)
                            val password = passphrase.toCharArray()
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    try {
                                        runCatching { BackupStore.decrypt(blob, password) }
                                    } finally {
                                        password.fill('\u0000')
                                    }
                                }
                                busy = false
                                result.onSuccess { data ->
                                    blob.fill(0)
                                    pendingImport = null
                                    passphrase = ""
                                    unlocked = data
                                    step = BackupStep.CONFIRM_RESTORE
                                }.onFailure {
                                    Toast.makeText(context, "incorrect passphrase or invalid backup", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else Modifier,
                    ),
                )
            }

            BackupStep.CONFIRM_RESTORE -> {
                val data = unlocked
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("replace current data?", color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        buildString {
                            append("${data?.cards?.size ?: 0} passes · ${data?.accounts?.size ?: 0} codes")
                            val skipped = data?.skippedPasses ?: 0
                            if (skipped > 0) {
                                append("\n$skipped ${if (skipped == 1) "pass needs" else "passes need"} a newer Paka and will be dropped.")
                            }
                            append("\nCurrent data will be replaced.")
                        },
                        color = Grey,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Spacer(Modifier.height(42.dp))
                    Text(
                        "restore",
                        color = if (data != null) White else Grey,
                        fontSize = 24.sp,
                        modifier = Modifier.fillMaxWidth().then(
                            if (data != null && !busy) tapModifier {
                                busy = true
                                scope.launch {
                                    val restored = onRestore(data)
                                    busy = false
                                    if (restored) {
                                        Toast.makeText(context, "backup restored", Toast.LENGTH_SHORT).show()
                                        resetToMenu()
                                        onBack()
                                    }
                                }
                            } else Modifier,
                        ),
                    )
                    Spacer(Modifier.height(28.dp))
                    Text("cancel", color = Grey, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier { resetToMenu() }))
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
