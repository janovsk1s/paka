package com.paka.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

private enum class Mode { CARDS, CODES }
private enum class ScanMode { CARD, CODE }
private enum class BackupStep { MENU, EXPORT_PASSWORD, IMPORT_PASSWORD, CONFIRM_RESTORE }
private enum class RestoreOutcome { SUCCESS, FAILED_ROLLED_BACK, FAILED_PARTIAL }
private enum class ManageGlyph { UP, DOWN }

private sealed interface Entry
private data class SingleEntry(val card: Card) : Entry
private data class StackEntry(val name: String, val cards: List<Card>) : Entry
private sealed interface PendingDuplicate {
    data class Pass(val candidate: Card, val existingName: String) : PendingDuplicate
    data class Code(val candidate: OtpAccount, val existingName: String) : PendingDuplicate
}

private data class ManageRow(val id: String, val name: String)
private data class RenameTarget(val id: String, val current: String, val isCard: Boolean)
private data class BarcodeRender(val bitmap: android.graphics.Bitmap?)
private data class InitialAppLoad(
    val cards: LoadOutcome<List<Card>>,
    val codes: LoadOutcome<List<OtpAccount>>,
)
private data class SaveRequest<T>(val value: T, val generation: Long)
private data class PendingClipboard(val value: String, val expiresAtMs: Long)

private sealed interface ManualCardItem {
    data object Name : ManualCardItem
    data object Data : ManualCardItem
    data class Format(val format: PakaFormat) : ManualCardItem
}

private enum class ManualCodeItem { NAME, ACCOUNT, SECRET }

private val MANUAL_FORMATS = listOf(
    PakaFormat.QR, PakaFormat.AZTEC, PakaFormat.PDF417, PakaFormat.DATA_MATRIX,
    PakaFormat.CODE128, PakaFormat.CODE39, PakaFormat.CODE93, PakaFormat.CODABAR,
    PakaFormat.ITF, PakaFormat.EAN13, PakaFormat.EAN8, PakaFormat.UPCA, PakaFormat.UPCE,
    PakaFormat.DATABAR_EXPANDED,
)

private fun PakaFormat.label(): String = when (this) {
    PakaFormat.QR -> "QR"
    PakaFormat.AZTEC -> "Aztec"
    PakaFormat.PDF417 -> "PDF417"
    PakaFormat.DATA_MATRIX -> "Data Matrix"
    PakaFormat.CODE128 -> "Code 128"
    PakaFormat.CODE39 -> "Code 39"
    PakaFormat.CODE93 -> "Code 93"
    PakaFormat.CODABAR -> "Codabar"
    PakaFormat.ITF -> "ITF"
    PakaFormat.EAN13 -> "EAN-13"
    PakaFormat.EAN8 -> "EAN-8"
    PakaFormat.UPCA -> "UPC-A"
    PakaFormat.UPCE -> "UPC-E"
    PakaFormat.DATABAR_EXPANDED -> "GS1 DataBar"
}

private fun buildEntries(cards: List<Card>): List<Entry> {
    val groupedStacks = linkedMapOf<String, MutableList<Card>>()
    cards.forEach { card ->
        card.stack?.let { stack -> groupedStacks.getOrPut(stack) { mutableListOf() }.add(card) }
    }
    val entries = mutableListOf<Entry>()
    val seen = mutableSetOf<String>()
    for (c in cards) {
        val s = c.stack
        if (s == null) entries.add(SingleEntry(c))
        else if (seen.add(s)) entries.add(StackEntry(s, groupedStacks.getValue(s)))
    }
    return entries
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

private fun <T> List<T>.moved(index: Int, up: Boolean): List<T> {
    val target = if (up) index - 1 else index + 1
    if (index < 0 || target < 0 || target >= size) return this
    val m = toMutableList()
    val t = m[index]; m[index] = m[target]; m[target] = t
    return m
}

class MainActivity : ComponentActivity() {
    private var homeResetSignal by mutableIntStateOf(0)
    private var resumeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setLabel(getString(R.string.app_name))
                    .setPrimaryColor(android.graphics.Color.BLACK)
                    .setBackgroundColor(android.graphics.Color.BLACK)
                    .setStatusBarColor(android.graphics.Color.BLACK)
                    .setNavigationBarColor(android.graphics.Color.BLACK)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(getString(R.string.app_name), null, android.graphics.Color.BLACK)
            },
        )
        setContent { PakaApp(homeResetSignal, resumeSignal) }
    }

    override fun onResume() {
        super.onResume()
        resumeSignal++
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Prefs.returnHome(this)) homeResetSignal++
    }
}

@Composable
fun PakaApp(homeResetSignal: Int = 0, resumeSignal: Int = 0) {
    val context = LocalContext.current
    var initialLoad by remember { mutableStateOf<InitialAppLoad?>(null) }
    LaunchedEffect(Unit) {
        initialLoad = withContext(Dispatchers.IO) {
            InitialAppLoad(
                cards = CardStore.load(context),
                codes = SecureStore.loadAccounts(context),
            )
        }
    }

    val loaded = initialLoad
    if (loaded == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text("Paka", color = White, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }
    LoadedPakaApp(homeResetSignal, resumeSignal, loaded.cards, loaded.codes)
}

@Composable
private fun LoadedPakaApp(
    homeResetSignal: Int,
    resumeSignal: Int,
    cardLoad: LoadOutcome<List<Card>>,
    codeLoad: LoadOutcome<List<OtpAccount>>,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var cards by remember { mutableStateOf(cardLoad.value) }
    var codes by remember { mutableStateOf(codeLoad.value) }
    var committedCards by remember { mutableStateOf(cardLoad.value) }
    var committedCodes by remember { mutableStateOf(codeLoad.value) }
    var cardsWritable by remember { mutableStateOf(cardLoad.writable) }
    var codesWritable by remember { mutableStateOf(codeLoad.writable) }
    val cardSaveQueue = remember { Channel<SaveRequest<List<Card>>>(Channel.UNLIMITED) }
    val codeSaveQueue = remember { Channel<SaveRequest<List<OtpAccount>>>(Channel.UNLIMITED) }
    val cardStoreMutex = remember { Mutex() }
    val codeStoreMutex = remember { Mutex() }
    val cardStorageGeneration = remember { AtomicLong(0L) }
    val codeStorageGeneration = remember { AtomicLong(0L) }
    var mode by remember { mutableStateOf(Mode.CARDS) }
    var showSettings by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var manageMode by remember { mutableStateOf<Mode?>(null) }
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    var pendingDuplicate by remember { mutableStateOf<PendingDuplicate?>(null) }
    var selectedCard by remember { mutableStateOf<Card?>(null) }
    var selectedStack by remember { mutableStateOf<String?>(null) }
    var detailCard by remember { mutableStateOf<Card?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanMode by remember { mutableStateOf(ScanMode.CARD) }
    var pendingScan by remember { mutableStateOf<ScanResult?>(null) }
    var manualCard by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf(false) }
    var showDev by remember { mutableStateOf(false) }
    var textSize by remember { mutableStateOf(Prefs.textSize(context)) }
    var vibrationEnabled by remember { mutableStateOf(Prefs.vibration(context)) }
    var returnHomeEnabled by remember { mutableStateOf(Prefs.returnHome(context)) }
    var autoLightEnabled by remember { mutableStateOf(Prefs.autoLight(context)) }
    var maxCodeBrightnessEnabled by remember { mutableStateOf(Prefs.maxCodeBrightness(context)) }
    var onboardingComplete by remember { mutableStateOf(Prefs.onboardingComplete(context)) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var pendingClipboard by remember { mutableStateOf<PendingClipboard?>(null) }
    val homeVisible =
        onboardingComplete && !showSettings && !showBackup && !showAbout && manageMode == null && renameTarget == null && pendingDuplicate == null && !showDev &&
        !manualCard && !manualCode && pendingScan == null && !scanning &&
        detailCard == null && selectedStack == null && selectedCard == null
    val codesVisible = mode == Mode.CODES && homeVisible
    val passWidthPx = with(density) { (configuration.screenWidthDp.dp - 32.dp).roundToPx() }
    LaunchedEffect(homeResetSignal) {
        if (homeResetSignal > 0) {
            mode = Mode.CARDS
            showSettings = false
            showBackup = false
            showAbout = false
            manageMode = null
            renameTarget = null
            pendingDuplicate = null
            selectedCard = null
            selectedStack = null
            detailCard = null
            scanning = false
            pendingScan = null
            manualCard = false
            manualCode = false
            showDev = false
        }
    }
    LaunchedEffect(codesVisible) {
        if (codesVisible) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    LaunchedEffect(homeVisible, mode, cards, passWidthPx) {
        if (homeVisible && mode == Mode.CARDS) {
            delay(250)
            cards.take(6).forEach { card ->
                withContext(Dispatchers.Default) {
                    Barcodes.generateCached(card.format, card.data, passWidthPx)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        cardLoad.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        codeLoad.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    LaunchedEffect(cardSaveQueue) {
        for (request in cardSaveQueue) {
            if (request.generation != cardStorageGeneration.get()) continue
            val result = withContext(Dispatchers.IO) {
                cardStoreMutex.withLock {
                    if (request.generation != cardStorageGeneration.get()) Result.success(Unit)
                    else CardStore.save(context, request.value)
                }
            }
            if (request.generation != cardStorageGeneration.get()) continue
            result.fold(
                onSuccess = { committedCards = request.value },
                onFailure = {
                    cardStorageGeneration.incrementAndGet()
                    cards = committedCards
                    cardsWritable = false
                    Toast.makeText(context, "Cards could not be saved", Toast.LENGTH_LONG).show()
                },
            )
        }
    }
    LaunchedEffect(codeSaveQueue) {
        for (request in codeSaveQueue) {
            if (request.generation != codeStorageGeneration.get()) continue
            val result = withContext(Dispatchers.IO) {
                codeStoreMutex.withLock {
                    if (request.generation != codeStorageGeneration.get()) Result.success(Unit)
                    else SecureStore.saveAccounts(context, request.value)
                }
            }
            if (request.generation != codeStorageGeneration.get()) continue
            result.fold(
                onSuccess = { committedCodes = request.value },
                onFailure = {
                    codeStorageGeneration.incrementAndGet()
                    codes = committedCodes
                    codesWritable = false
                    Toast.makeText(context, "2FA accounts could not be saved", Toast.LENGTH_LONG).show()
                },
            )
        }
    }
    DisposableEffect(cardSaveQueue, codeSaveQueue) {
        onDispose {
            cardSaveQueue.close()
            codeSaveQueue.close()
        }
    }

    LaunchedEffect(pendingClipboard, resumeSignal) {
        val pending = pendingClipboard ?: return@LaunchedEffect
        delay((pending.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
        // Android 10+ hides clipboard contents while an app lacks focus. Keep the
        // pending marker and retry on resume instead of blindly erasing newer data.
        delay(150)
        val activity = context as? Activity
        if (activity?.hasWindowFocus() != true) return@LaunchedEffect
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == pending.value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        pendingClipboard = null
    }

    fun saveCards(list: List<Card>): Boolean {
        if (!cardsWritable) {
            Toast.makeText(context, "Cards are read-only until storage is recovered", Toast.LENGTH_LONG).show()
            return false
        }
        cards = list
        val queued = cardSaveQueue.trySend(SaveRequest(list, cardStorageGeneration.get())).isSuccess
        if (!queued) {
            cards = committedCards
            Toast.makeText(context, "Cards could not be queued for saving", Toast.LENGTH_LONG).show()
        }
        return queued
    }

    fun saveCodes(list: List<OtpAccount>): Boolean {
        if (!codesWritable) {
            Toast.makeText(context, "2FA accounts are read-only until storage is recovered", Toast.LENGTH_LONG).show()
            return false
        }
        codes = list
        val queued = codeSaveQueue.trySend(SaveRequest(list, codeStorageGeneration.get())).isSuccess
        if (!queued) {
            codes = committedCodes
            Toast.makeText(context, "2FA accounts could not be queued for saving", Toast.LENGTH_LONG).show()
        }
        return queued
    }

    val updateCard: (Card) -> Boolean = { u -> saveCards(cards.map { if (it.id == u.id) u else it }) }
    fun addCard(card: Card, allowDuplicate: Boolean = false): Boolean {
        if (!allowDuplicate) {
            cards.firstOrNull { it.format == card.format && it.data == card.data }?.let { existing ->
                pendingDuplicate = PendingDuplicate.Pass(card, existing.name)
                return false
            }
        }
        if (!saveCards(cards + card)) return false
        manualCard = false
        pendingScan = null
        selectedCard = card
        return true
    }
    fun addCode(account: OtpAccount, allowDuplicate: Boolean = false): Boolean {
        if (!allowDuplicate) {
            codes.firstOrNull { it.duplicateKey() == account.duplicateKey() }?.let { existing ->
                pendingDuplicate = PendingDuplicate.Code(account, existing.title())
                return false
            }
        }
        if (!saveCodes(codes + account)) return false
        manualCode = false
        mode = Mode.CODES
        return true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanning = true
        else Toast.makeText(context, "Camera permission is needed to scan codes", Toast.LENGTH_LONG).show()
    }
    val startScan = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    ProtectSensitiveContent(mode == Mode.CODES || manualCode || showBackup || (scanning && scanMode == ScanMode.CODE))

    // ---- routing (topmost overlay wins) ----

    if (!onboardingComplete) {
        OnboardingScreen(
            onStart = {
                Prefs.setOnboardingComplete(context)
                onboardingComplete = true
            },
        )
        return
    }

    when (val duplicate = pendingDuplicate) {
        is PendingDuplicate.Pass -> {
            DuplicateConfirmScreen(
                kind = "pass",
                existingName = duplicate.existingName,
                onConfirm = {
                    if (addCard(duplicate.candidate, allowDuplicate = true)) pendingDuplicate = null
                },
                onBack = {
                    pendingDuplicate = null
                    manualCard = false
                    pendingScan = null
                },
            )
            return
        }
        is PendingDuplicate.Code -> {
            DuplicateConfirmScreen(
                kind = "code",
                existingName = duplicate.existingName,
                onConfirm = {
                    if (addCode(duplicate.candidate, allowDuplicate = true)) pendingDuplicate = null
                },
                onBack = {
                    pendingDuplicate = null
                    manualCode = false
                },
            )
            return
        }
        null -> Unit
    }

    val rename = renameTarget
    if (rename != null) {
        TextEntryScreen(
            title = "rename",
            initial = rename.current,
            onSave = { newName ->
                val saved = if (rename.isCard) saveCards(cards.map { if (it.id == rename.id) it.copy(name = newName) else it })
                else saveCodes(codes.map { if (it.id == rename.id) it.copy(issuer = newName) else it })
                if (saved) renameTarget = null
            },
            onBack = { renameTarget = null },
        )
        return
    }

    val managing = manageMode
    if (managing != null) {
        val rows = if (managing == Mode.CARDS) cards.map { ManageRow(it.id, it.name) }
        else codes.map { ManageRow(it.id, it.title()) }
        ManageScreen(
            rows = rows,
            onRename = { id ->
                if (managing == Mode.CARDS) cards.firstOrNull { it.id == id }?.let { renameTarget = RenameTarget(it.id, it.name, true) }
                else codes.firstOrNull { it.id == id }?.let { renameTarget = RenameTarget(it.id, it.issuer, false) }
            },
            onUp = { id ->
                if (managing == Mode.CARDS) saveCards(cards.moved(cards.indexOfFirst { it.id == id }, true))
                else saveCodes(codes.moved(codes.indexOfFirst { it.id == id }, true))
            },
            onDown = { id ->
                if (managing == Mode.CARDS) saveCards(cards.moved(cards.indexOfFirst { it.id == id }, false))
                else saveCodes(codes.moved(codes.indexOfFirst { it.id == id }, false))
            },
            onDelete = { id ->
                if (managing == Mode.CARDS) saveCards(cards.filter { it.id != id })
                else saveCodes(codes.filter { it.id != id })
            },
            onBack = { manageMode = null },
        )
        return
    }

    if (showDev) {
        DevScreen(
            textSize = textSize,
            onTextSize = { textSize = it; Prefs.setTextSize(context, it) },
            returnHomeEnabled = returnHomeEnabled,
            onReturnHome = { enabled ->
                returnHomeEnabled = enabled
                Prefs.setReturnHome(context, enabled)
            },
            autoLightEnabled = autoLightEnabled,
            onAutoLight = { enabled ->
                autoLightEnabled = enabled
                Prefs.setAutoLight(context, enabled)
            },
            maxCodeBrightnessEnabled = maxCodeBrightnessEnabled,
            onMaxCodeBrightness = { enabled ->
                maxCodeBrightnessEnabled = enabled
                Prefs.setMaxCodeBrightness(context, enabled)
            },
            onBack = { showDev = false },
        )
        return
    }

    if (showAbout) {
        AboutScreen(onDev = { showDev = true }, onBack = { showAbout = false })
        return
    }

    if (showBackup) {
        BackupScreen(
            cards = cards,
            accounts = codes,
            onRestore = { restored ->
                if (!cardsWritable || !codesWritable) {
                    Toast.makeText(context, "Storage must be healthy before restoring", Toast.LENGTH_LONG).show()
                    false
                } else {
                    val oldCards = cards
                    val oldCodes = codes
                    cardStorageGeneration.incrementAndGet()
                    codeStorageGeneration.incrementAndGet()
                    val outcome = withContext(Dispatchers.IO) {
                        codeStoreMutex.withLock {
                            cardStoreMutex.withLock {
                                val codesSaved = SecureStore.saveAccounts(context, restored.accounts)
                                if (codesSaved.isFailure) RestoreOutcome.FAILED_ROLLED_BACK
                                else if (CardStore.save(context, restored.cards).isSuccess) RestoreOutcome.SUCCESS
                                else if (SecureStore.saveAccounts(context, oldCodes).isSuccess) RestoreOutcome.FAILED_ROLLED_BACK
                                else RestoreOutcome.FAILED_PARTIAL
                            }
                        }
                    }
                    when (outcome) {
                        RestoreOutcome.SUCCESS -> {
                            cards = restored.cards
                            codes = restored.accounts
                            committedCards = restored.cards
                            committedCodes = restored.accounts
                            true
                        }
                        RestoreOutcome.FAILED_ROLLED_BACK -> {
                            cards = oldCards
                            codes = oldCodes
                            committedCards = oldCards
                            committedCodes = oldCodes
                            Toast.makeText(context, "Backup could not be restored", Toast.LENGTH_LONG).show()
                            false
                        }
                        RestoreOutcome.FAILED_PARTIAL -> {
                            cards = oldCards
                            codes = restored.accounts
                            committedCards = oldCards
                            committedCodes = restored.accounts
                            Toast.makeText(context, "Restore was interrupted; reopen Paka to verify data", Toast.LENGTH_LONG).show()
                            false
                        }
                    }
                }
            },
            onBack = { showBackup = false },
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            onReorder = { manageMode = mode },
            onBackup = { showBackup = true },
            vibrationEnabled = vibrationEnabled,
            onVibration = { enabled ->
                vibrationEnabled = enabled
                Prefs.setVibration(context, enabled)
            },
            onAbout = { showAbout = true },
            onBack = { showSettings = false },
        )
        return
    }

    if (manualCard) {
        ManualCardScreen(
            onSave = { card -> addCard(card) },
            onBack = { manualCard = false },
        )
        return
    }

    if (manualCode) {
        ManualCodeScreen(onSave = { addCode(it) }, onBack = { manualCode = false })
        return
    }

    val pending = pendingScan
    if (pending != null) {
        TextEntryScreen(
            title = "name",
            initial = "",
            onSave = { name ->
                val card = Card(name = name, data = pending.data, format = pending.format)
                addCard(card)
            },
            onBack = { pendingScan = null },
        )
        return
    }

    if (scanning) {
        BackHandler { scanning = false }
        ScanScreen(
            automaticLightEnabled = autoLightEnabled,
            onScanned = { result ->
                scanning = false
                if (scanMode == ScanMode.CODE) {
                    val account = Totp.parseOtpauth(result.data)
                    if (account != null) addCode(account)
                    else Toast.makeText(context, "Not a 2FA QR code", Toast.LENGTH_SHORT).show()
                } else {
                    pendingScan = result
                }
            },
            onBack = { scanning = false },
        )
        return
    }

    val detail = detailCard
    if (detail != null) {
        val fresh = cards.firstOrNull { it.id == detail.id } ?: detail
        CardDetail(card = fresh, onUpdate = updateCard, onBack = { detailCard = null })
        return
    }

    val stackName = selectedStack
    if (stackName != null) {
        val stackCards = cards.filter { it.stack == stackName }
        BackHandler { selectedStack = null }
        StackScreen(
            name = stackName,
            cards = stackCards,
            forceMaximumBrightness = maxCodeBrightnessEnabled,
            onLongCurrent = { detailCard = it },
            onBack = { selectedStack = null },
        )
        return
    }

    val card = selectedCard
    if (card != null) {
        val fresh = cards.firstOrNull { it.id == card.id } ?: card
        BackHandler { selectedCard = null }
        CardScreen(
            card = fresh,
            forceMaximumBrightness = maxCodeBrightnessEnabled,
            onLong = { detailCard = fresh },
            onBack = { selectedCard = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Text("Paka", color = White, fontSize = 16.sp, fontWeight = FontWeight.Normal)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                Mode.CARDS ->
                    if (cards.isEmpty()) EmptyHint("tap + to add a card")
                    else CardsList(
                        entries = buildEntries(cards),
                        textSize = textSize,
                        onOpenCard = { selectedCard = it },
                        onOpenStack = { selectedStack = it },
                        onDetail = { detailCard = it },
                    )
                Mode.CODES ->
                    if (codes.isEmpty()) EmptyHint("tap + to add a code")
                    else CodesList(
                        accounts = codes,
                        nowMs = nowMs,
                        textSize = textSize,
                        onCopy = { code ->
                            if (code.any { it == '-' }) return@CodesList
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val data = ClipData.newPlainText("2FA code", code)
                            data.description.extras = PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            clip.setPrimaryClip(data)
                            pendingClipboard = PendingClipboard(
                                value = code,
                                expiresAtMs = System.currentTimeMillis() + 30_000L,
                            )
                            Toast.makeText(context, "copied", Toast.LENGTH_SHORT).show()
                        },
                    )
            }
        }

        BottomBar(
            mode = mode,
            onSettings = { showSettings = true },
            onAdd = { scanMode = if (mode == Mode.CARDS) ScanMode.CARD else ScanMode.CODE; startScan() },
            onAddLong = { if (mode == Mode.CARDS) manualCard = true else manualCode = true },
            onToggleMode = { mode = if (mode == Mode.CARDS) Mode.CODES else Mode.CARDS },
        )
    }
}

@Composable
private fun SimpleTopBar(title: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        BackArrow(modifier = Modifier.align(Alignment.CenterStart).offset(x = (-30).dp), onBack = onBack)
        Text(title.replaceFirstChar { it.uppercase() }, color = White, fontSize = 16.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun OnboardingScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Text("Welcome", color = White, fontSize = 16.sp, fontWeight = FontWeight.Normal)
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, end = 14.dp, bottom = 8.dp)) {
            OnboardingRow(weight = 1f) {
                Text("Paka", color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
            }
            OnboardingRow(weight = 1f) {
                Text("Passes and 2FA codes, carried lightly.", color = White, fontSize = 20.sp, fontWeight = FontWeight.Normal)
            }
            OnboardingRow(weight = 1f) {
                Text("Tap + to scan. Long-press + to enter manually.", color = White, fontSize = 20.sp, fontWeight = FontWeight.Normal)
            }
            OnboardingRow(weight = 1f) {
                Text("Swipe between pages. Long-press passes for details. Data stays encrypted on this phone.", color = White, fontSize = 20.sp, fontWeight = FontWeight.Normal)
            }
            OnboardingRow(weight = 1f) {
                Text(
                    "start",
                    color = White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().then(tapModifier(onStart)),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.OnboardingRow(weight: Float, content: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(weight).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        content()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Normal)
    }
}

/** A vertically scrolling column with a proportional scrollbar shown only on overflow. */
@Composable
private fun ScrollList(topPadding: Dp = 44.dp, spacing: Dp = 36.dp, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberHapticScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = topPadding, end = 14.dp, bottom = 8.dp).hardSnapVerticalScroll(state),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        VerticalScrollbar(state, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun rememberHapticScrollState(): ScrollState {
    return rememberScrollState()
}

/** Follow the finger while dragging, then cut immediately to the nearest viewport boundary. */
@Composable
private fun Modifier.hardSnapVerticalScroll(state: ScrollState): Modifier {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var viewportPx by remember { mutableIntStateOf(1) }
    var settledValue by remember(state) { mutableIntStateOf(state.value) }
    val flingBehavior = remember(state) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val page = viewportPx.coerceAtLeast(1)
                val current = state.value
                val lower = (current / page) * page
                val upper = min(lower + page, state.maxValue)
                val target = listOf(lower, upper, state.maxValue)
                    .distinct()
                    .minByOrNull { abs(it - current) }
                    ?: current
                scrollBy((target - current).toFloat())
                if (target != settledValue) performPakaHaptic(context, haptics)
                settledValue = target
                return 0f
            }
        }
    }
    return onSizeChanged { viewportPx = it.height.coerceAtLeast(1) }
        .verticalScroll(state, flingBehavior = flingBehavior)
}

/** Thin white track + thicker white segment, square ends, sized to the visible portion. */
@Composable
private fun VerticalScrollbar(state: ScrollState, modifier: Modifier) {
    if (state.maxValue <= 0) return
    Canvas(modifier = modifier.offset(x = 18.dp).fillMaxHeight().width(6.dp)) {
        val vh = size.height
        val maxV = state.maxValue.toFloat()
        val total = vh + maxV
        val margin = 6.dp.toPx()
        val trackLen = (vh - 2 * margin).coerceAtLeast(1f)
        val thumbLen = (vh / total * trackLen).coerceIn(24.dp.toPx(), trackLen)
        val thumbY = margin + (state.value / maxV) * (trackLen - thumbLen)
        val centerX = size.width / 2f
        drawRect(White.copy(alpha = 0.3f), topLeft = Offset(centerX - 0.5.dp.toPx(), margin), size = Size(1.dp.toPx(), trackLen))
        drawRect(White, topLeft = Offset(centerX - 2.dp.toPx(), thumbY), size = Size(4.dp.toPx(), thumbLen))
    }
}

private const val ITEMS_PER_PAGE = 5

/** Five fixed row slots per page. Swipes replace the page instantly on release. */
@Composable
private fun <T> PagedList(items: List<T>, content: @Composable (T) -> Unit) {
    val pages = remember(items) { items.chunked(ITEMS_PER_PAGE) }
    if (pages.isEmpty()) return
    HardCutPager(pageCount = pages.size) { currentPage, _ ->
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp, end = 14.dp, bottom = 8.dp),
        ) {
            pages[currentPage].forEach { item ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    content(item)
                }
            }
            repeat(ITEMS_PER_PAGE - pages[currentPage].size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HardCutPager(
    pageCount: Int,
    modifier: Modifier = Modifier,
    indicatorOffset: Dp = 18.dp,
    showIndicator: Boolean = true,
    content: @Composable (Int, () -> Unit) -> Unit,
) {
    if (pageCount <= 0) return
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var page by remember { mutableIntStateOf(0) }
    val currentPage = page.coerceIn(0, pageCount - 1)

    LaunchedEffect(pageCount) {
        if (page >= pageCount) page = pageCount - 1
    }

    Box(
        modifier = modifier.fillMaxSize().pointerInput(pageCount, currentPage) {
            val threshold = 24.dp.toPx()
            var dragDistance = 0f
            var feedbackSent = false
            detectVerticalDragGestures(
                onDragStart = {
                    dragDistance = 0f
                    feedbackSent = false
                },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    dragDistance += amount
                    val canChangePage =
                        (dragDistance <= -threshold && currentPage < pageCount - 1) ||
                            (dragDistance >= threshold && currentPage > 0)
                    if (!feedbackSent && canChangePage) {
                        performPakaHaptic(context, haptics)
                        feedbackSent = true
                    }
                },
                onDragEnd = {
                    val destination = when {
                        dragDistance <= -threshold -> (currentPage + 1).coerceAtMost(pageCount - 1)
                        dragDistance >= threshold -> (currentPage - 1).coerceAtLeast(0)
                        else -> currentPage
                    }
                    page = destination
                },
                onDragCancel = { dragDistance = 0f },
            )
        },
    ) {
        content(currentPage) { page = (currentPage + 1) % pageCount }
        if (showIndicator && pageCount > 1) {
            PageIndicator(
                page = currentPage,
                pageCount = pageCount,
                horizontalOffset = indicatorOffset,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun PageIndicator(page: Int, pageCount: Int, horizontalOffset: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.offset(x = horizontalOffset).fillMaxHeight().width(6.dp)) {
        val topMargin = 20.dp.toPx()
        val bottomMargin = 6.dp.toPx()
        val trackLength = (size.height - topMargin - bottomMargin).coerceAtLeast(1f)
        val thumbLength = (trackLength / pageCount).coerceAtLeast(24.dp.toPx()).coerceAtMost(trackLength)
        val availableTravel = trackLength - thumbLength
        val thumbY = topMargin + if (pageCount <= 1) 0f else availableTravel * page / (pageCount - 1)
        val centerX = size.width / 2f
        drawRect(
            White.copy(alpha = 0.3f),
            topLeft = Offset(centerX - 0.5.dp.toPx(), topMargin),
            size = Size(1.dp.toPx(), trackLength),
        )
        drawRect(
            White,
            topLeft = Offset(centerX - 2.dp.toPx(), thumbY),
            size = Size(4.dp.toPx(), thumbLength),
        )
    }
}

@Composable
private fun CardsList(entries: List<Entry>, textSize: Float, onOpenCard: (Card) -> Unit, onOpenStack: (String) -> Unit, onDetail: (Card) -> Unit) {
    PagedList(entries) { entry ->
        when (entry) {
            is SingleEntry -> Text(
                text = entry.card.name,
                color = White, fontSize = textSize.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().then(tapLongModifier(onClick = { onOpenCard(entry.card) }, onLongClick = { onDetail(entry.card) })),
            )
            is StackEntry -> Row(
                modifier = Modifier.fillMaxWidth().then(tapModifier { onOpenStack(entry.name) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.name, color = White, fontSize = textSize.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${entry.cards.size}", color = Grey, fontSize = 16.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
private fun CodesList(accounts: List<OtpAccount>, nowMs: Long, textSize: Float, onCopy: (String) -> Unit) {
    PagedList(accounts) { account ->
        val code = Totp.code(account, nowMs)
        val remaining = Totp.secondsRemaining(account, nowMs)
        // Keep the large code on the same vertical centerline as a pass name.
        // The secondary account label is overlaid above it so it cannot push
        // the primary content down when switching modes.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(tapModifier { onCopy(code) }),
        ) {
            Text(
                account.title(),
                color = Grey,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).offset(y = (-10).dp),
            )
            Row(modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                Text(formatCode(code), color = White, fontSize = textSize.sp, fontWeight = FontWeight.Normal, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Text("$remaining", color = Grey, fontSize = 16.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

private fun formatCode(code: String): String = when (code.length) {
    6 -> "${code.substring(0, 3)} ${code.substring(3)}"
    7, 8 -> "${code.substring(0, 4)} ${code.substring(4)}"
    else -> code
}

private fun OtpAccount.duplicateKey(): String = listOf(
    secret.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT),
    digits.toString(),
    period.toString(),
    algorithm.uppercase(Locale.ROOT),
).joinToString(":")

@Composable
private fun SettingsScreen(
    onReorder: () -> Unit,
    onBackup: () -> Unit,
    vibrationEnabled: Boolean,
    onVibration: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("settings", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(listOf("reorder", "backup", "vibration", "about")) { item ->
                SettingsItem(
                    label = item,
                    trailing = if (item == "vibration") if (vibrationEnabled) "on" else "off" else null,
                    onClick = {
                        when (item) {
                            "reorder" -> onReorder()
                            "backup" -> onBackup()
                            "vibration" -> {
                                val enabled = !vibrationEnabled
                                onVibration(enabled)
                                if (enabled) performPakaHaptic(context, haptics)
                            }
                            else -> onAbout()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AboutScreen(onDev: () -> Unit, onBack: () -> Unit) {
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    val hiddenDeveloperTap = {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 600) taps + 1 else 1
        lastTap = now
        if (taps >= 3) {
            taps = 0
            onDev()
        }
    }
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("about", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp, end = 14.dp, bottom = 8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Row(
                        modifier = Modifier.fillMaxWidth().then(tapModifier(hiddenDeveloperTap)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Paka",
                            color = White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            color = Grey,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                }
                Box(Modifier.weight(3f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "Latvian for “package”. Saves passes and carries 2FA codes in a light way. Long-presses may reveal more options.",
                        color = White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Column {
                        Text("From a Latvian in Vienna.", color = White, fontSize = 20.sp, fontWeight = FontWeight.Normal)
                        Text("@janovsk1s", color = Grey, fontSize = 16.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsItem(label: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().then(tapModifier(onClick)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun BackupScreen(
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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
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
        unlocked = null
        passphrase = ""
        confirmation = ""
        busy = false
        step = BackupStep.MENU
    }

    val goBack: () -> Unit = {
        if (step == BackupStep.MENU) onBack() else resetToMenu()
    }
    BackHandler { goBack() }

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
                                    else importLauncher.launch(arrayOf("application/octet-stream", "application/*"))
                                },
                            ),
                        )
                    }
                    Text(
                        "Backups contain all passes and 2FA secrets. They are encrypted offline with your passphrase.",
                        color = Grey,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.align(Alignment.BottomStart).padding(end = 14.dp, bottom = 20.dp),
                    )
                }
            }

            BackupStep.EXPORT_PASSWORD -> {
                val canExport = passphrase.length >= 8 && passphrase == confirmation && !busy
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 28.dp).hardSnapVerticalScroll(rememberHapticScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text("Use at least 8 characters. This passphrase cannot be recovered.", color = Grey, fontSize = 16.sp)
                    EditField("passphrase", passphrase, { passphrase = it }, "8+ characters", visualTransformation = PasswordVisualTransformation())
                    EditField("repeat", confirmation, { confirmation = it }, "repeat passphrase", visualTransformation = PasswordVisualTransformation())
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
                                val result = withContext(Dispatchers.Default) {
                                    try {
                                        runCatching { BackupStore.encrypt(cards, accounts, password) }
                                    } finally {
                                        password.fill('\u0000')
                                    }
                                }
                                busy = false
                                result.onSuccess { bytes ->
                                    pendingExport = bytes
                                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    exportLauncher.launch("paka-$stamp.paka")
                                }.onFailure {
                                    Toast.makeText(context, "backup could not be encrypted", Toast.LENGTH_LONG).show()
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
                    EditField("passphrase", passphrase, { passphrase = it }, "backup passphrase", visualTransformation = PasswordVisualTransformation())
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
                        "${data?.cards?.size ?: 0} passes · ${data?.accounts?.size ?: 0} codes\nCurrent data will be replaced.",
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

@Composable
private fun DevScreen(
    textSize: Float,
    onTextSize: (Float) -> Unit,
    returnHomeEnabled: Boolean,
    onReturnHome: (Boolean) -> Unit,
    autoLightEnabled: Boolean,
    onAutoLight: (Boolean) -> Unit,
    maxCodeBrightnessEnabled: Boolean,
    onMaxCodeBrightness: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("developer", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(listOf(0, 1, 2, 3, 4, 5)) { item ->
                when (item) {
                    0 -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("text size", color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                        Text("${textSize.toInt()} sp", color = White, fontSize = 18.sp, fontWeight = FontWeight.Normal)
                    }
                    1 -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "−",
                            color = White,
                            fontSize = 30.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f).then(tapModifier { onTextSize((textSize - 1f).coerceAtLeast(16f)) }),
                        )
                        Text(
                            "+",
                            color = White,
                            fontSize = 30.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).then(tapModifier { onTextSize((textSize + 1f).coerceAtMost(64f)) }),
                        )
                    }
                    2 -> Text(
                        "KlimaTicket",
                        color = White,
                        fontSize = textSize.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    3 -> SettingsItem(
                        label = "return home",
                        trailing = if (returnHomeEnabled) "on" else "off",
                        onClick = { onReturnHome(!returnHomeEnabled) },
                    )
                    4 -> SettingsItem(
                        label = "auto light",
                        trailing = if (autoLightEnabled) "on" else "off",
                        onClick = { onAutoLight(!autoLightEnabled) },
                    )
                    else -> SettingsItem(
                        label = "max brightness",
                        trailing = if (maxCodeBrightnessEnabled) "on" else "off",
                        onClick = { onMaxCodeBrightness(!maxCodeBrightnessEnabled) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageScreen(
    rows: List<ManageRow>,
    onRename: (String) -> Unit,
    onUp: (String) -> Unit,
    onDown: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ManageRow?>(null) }
    var actionRow by remember { mutableStateOf<ManageRow?>(null) }
    val deleting = pendingDelete
    if (deleting != null) {
        ConfirmDeleteScreen(
            name = deleting.name,
            onConfirm = { onDelete(deleting.id); pendingDelete = null },
            onBack = { pendingDelete = null },
        )
        return
    }
    val editing = actionRow
    if (editing != null) {
        ManageItemScreen(
            row = editing,
            onRename = { onRename(editing.id) },
            onDelete = {
                actionRow = null
                pendingDelete = editing
            },
            onBack = { actionRow = null },
        )
        return
    }
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("reorder", onBack)
        PagedList(rows) { row ->
                val index = rows.indexOfFirst { it.id == row.id }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.name,
                        color = White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).then(tapModifier { actionRow = row }),
                    )
                    ManageGlyphAction(ManageGlyph.UP, "Move ${row.name} up", Modifier.width(48.dp), enabled = index > 0) { onUp(row.id) }
                    ManageGlyphAction(ManageGlyph.DOWN, "Move ${row.name} down", Modifier.width(48.dp), enabled = index < rows.lastIndex) { onDown(row.id) }
                }
        }
    }
}

@Composable
private fun ManageItemScreen(row: ManageRow, onRename: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("edit", onBack)
        PagedList(listOf(0, 1, 2)) { item ->
            val label = when (item) {
                0 -> row.name
                1 -> "rename"
                else -> "delete"
            }
            Text(
                text = label,
                color = if (item == 0) Grey else White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().then(
                    when (item) {
                        1 -> tapModifier(onRename)
                        2 -> tapModifier(onDelete)
                        else -> Modifier
                    },
                ),
            )
        }
    }
}

@Composable
private fun ManageGlyphAction(
    glyph: ManageGlyph,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Grey.copy(alpha = 0.7f)
        else -> White
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .then(if (enabled) tapModifier(onClick, description) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            when (glyph) {
                ManageGlyph.UP -> {
                    drawLine(color, Offset(size.width * 0.27f, size.height * 0.59f), Offset(size.width * 0.5f, size.height * 0.36f), stroke, StrokeCap.Square)
                    drawLine(color, Offset(size.width * 0.5f, size.height * 0.36f), Offset(size.width * 0.73f, size.height * 0.59f), stroke, StrokeCap.Square)
                }
                ManageGlyph.DOWN -> {
                    drawLine(color, Offset(size.width * 0.27f, size.height * 0.41f), Offset(size.width * 0.5f, size.height * 0.64f), stroke, StrokeCap.Square)
                    drawLine(color, Offset(size.width * 0.5f, size.height * 0.64f), Offset(size.width * 0.73f, size.height * 0.41f), stroke, StrokeCap.Square)
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteScreen(name: String, onConfirm: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("delete $name?", color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(44.dp))
        Text("delete", color = White, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onConfirm)))
        Spacer(Modifier.height(28.dp))
        Text("cancel", color = Grey, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onBack)))
    }
}

@Composable
private fun DuplicateConfirmScreen(kind: String, existingName: String, onConfirm: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$kind already added", color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(18.dp))
        Text("matches $existingName", color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(44.dp))
        Text("add anyway", color = White, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onConfirm)))
        Spacer(Modifier.height(28.dp))
        Text("cancel", color = Grey, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onBack)))
    }
}

@Composable
private fun ManualCardScreen(onSave: (Card) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var data by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(PakaFormat.QR) }
    val trimmedData = data.trim()
    val renderKey = "${format.name}:$trimmedData"
    val basicValidationError = trimmedData.takeIf { it.isNotBlank() }?.let { Barcodes.validationError(format, it) }
    var renderCheck by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val renderable = renderCheck?.takeIf { it.first == renderKey }?.second
    val validating = trimmedData.isNotBlank() && basicValidationError == null && renderable == null
    val validationError = basicValidationError ?: if (renderable == false) "Code cannot be rendered exactly" else null
    val canSave = name.isNotBlank() && trimmedData.isNotBlank() && validationError == null && renderable == true
    val items = remember { listOf(ManualCardItem.Name, ManualCardItem.Data) + MANUAL_FORMATS.map(ManualCardItem::Format) }
    BackHandler { onBack() }

    LaunchedEffect(renderKey, basicValidationError) {
        if (trimmedData.isBlank() || basicValidationError != null) return@LaunchedEffect
        delay(120)
        val verified = withContext(Dispatchers.Default) {
            val bitmap = Barcodes.generate(format, trimmedData, 320)
            (bitmap != null).also { bitmap?.recycle() }
        }
        renderCheck = renderKey to verified
    }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("card", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(items) { item ->
                when (item) {
                    ManualCardItem.Name -> EditField("name", name, { name = it }, "e.g. Billa")
                    ManualCardItem.Data -> EditField("data", data, { data = it }, "code content")
                    is ManualCardItem.Format -> {
                        val f = item.format
                        Row(
                            modifier = Modifier.fillMaxWidth().then(tapModifier { format = f }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = f.label(),
                                color = if (f == format) White else Grey,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.weight(1f),
                            )
                            if (f == format) Text("selected", color = Grey, fontSize = 14.sp, fontWeight = FontWeight.Light)
                        }
                    }
                }
            }
        }
        val actionText = validationError ?: if (validating) "checking…" else "save"
        Text(
            text = actionText,
            color = if (canSave) White else Grey,
            fontSize = if (validationError == null) 18.sp else 14.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 18.dp).then(
                if (canSave) tapModifier { onSave(Card(name = name.trim(), data = trimmedData, format = format)) }
                else Modifier,
            ),
        )
    }
}

@Composable
private fun ManualCodeScreen(onSave: (OtpAccount) -> Unit, onBack: () -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val draft = OtpAccount(
        issuer = issuer.trim(),
        account = account.trim(),
        secret = secret.replace(" ", "").trim(),
    )
    val validationError = if (issuer.isBlank() || secret.isBlank()) null else Totp.validationError(draft)
    val canSave = issuer.isNotBlank() && secret.isNotBlank() && validationError == null
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("code", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(ManualCodeItem.entries) { item ->
                when (item) {
                    ManualCodeItem.NAME -> EditField("name", issuer, { issuer = it }, "e.g. GitHub")
                    ManualCodeItem.ACCOUNT -> EditField("account", account, { account = it }, "optional")
                    ManualCodeItem.SECRET -> EditField(
                        "secret",
                        secret,
                        { secret = it },
                        "base32 key",
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        }
        val actionText = validationError ?: "save"
        Text(
            text = actionText,
            color = if (canSave) White else Grey,
            fontSize = if (validationError == null) 18.sp else 14.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 18.dp).then(if (canSave) tapModifier { onSave(draft) } else Modifier),
        )
    }
}

@Composable
private fun TextEntryScreen(title: String, initial: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { onBack() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(title, onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal),
                    cursorBrush = SolidColor(White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { keyboard?.hide(); onSave(text.trim()) } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(White))
            }
        }
        Text(
            text = "save",
            color = if (text.isBlank()) Grey else White,
            fontSize = 18.sp,
            modifier = Modifier.padding(vertical = 18.dp).then(tapModifier { if (text.isNotBlank()) onSave(text.trim()) }),
        )
    }
}

@Composable
private fun CardDetail(card: Card, onUpdate: (Card) -> Boolean, onBack: () -> Unit) {
    var notes by remember(card.id) { mutableStateOf(card.notes) }
    var stack by remember(card.id) { mutableStateOf(card.stack ?: "") }
    val persistAndBack = {
        if (onUpdate(card.copy(notes = notes.trim(), stack = stack.trim().ifBlank { null }))) onBack()
    }
    BackHandler { persistAndBack() }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("details", persistAndBack)
        HardCutPager(pageCount = 2, modifier = Modifier.weight(1f).fillMaxWidth()) { page, _ ->
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (page == 0) {
                    Column {
                        FieldLabel("name")
                        Spacer(Modifier.height(4.dp))
                        Text(card.name, color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Grey.copy(alpha = 0.5f)))
                    EditField("stack", stack, { stack = it }, "none")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LabelValue("format", card.format.label(), Modifier.weight(1f))
                        LabelValue("added", formatDate(card.createdAt), Modifier.weight(1f))
                    }
                } else {
                    Column {
                        FieldLabel("code")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            card.data,
                            color = Grey,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Grey.copy(alpha = 0.5f)))
                    EditField("notes", notes, { notes = it }, "add a note", singleLine = false)
                }
            }
        }
        Text(
            text = "save",
            color = White,
            fontSize = 18.sp,
            modifier = Modifier.padding(vertical = 18.dp).then(tapModifier(persistAndBack)),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Grey, fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 2.sp)
}

@Composable
private fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(value, color = White, fontSize = 20.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column {
        FieldLabel(label)
        Spacer(Modifier.height(6.dp))
        Box {
            if (value.isEmpty()) Text(placeholder, color = Grey, fontSize = 20.sp, fontWeight = FontWeight.Light)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                textStyle = TextStyle(color = White, fontSize = 20.sp, fontWeight = FontWeight.Light),
                cursorBrush = SolidColor(White),
                modifier = Modifier.fillMaxWidth().then(if (singleLine) Modifier else Modifier.height(120.dp)),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Grey))
    }
}

@Composable
private fun BarcodePanel(
    card: Card,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    preRendered: BarcodeRender? = null,
    usePreRendered: Boolean = false,
    longPressOnly: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(White)
            .then(
                if (longPressOnly) longPressModifier(onLongClick = onLongClick, label = card.name)
                else tapLongModifier(onClick = onClick, onLongClick = onLongClick, label = card.name),
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val targetWidthPx = with(density) { maxWidth.roundToPx() }
            val render = if (usePreRendered) preRendered else rememberBarcodeRender(card, targetWidthPx)
            val bitmap = render?.bitmap
            when {
                bitmap != null -> {
                    val painter = remember(bitmap) {
                        BitmapPainter(bitmap.asImageBitmap(), filterQuality = FilterQuality.None)
                    }
                    val imageWidth = with(density) { bitmap.width.toDp() }
                    val imageHeight = with(density) { bitmap.height.toDp() }
                    Image(
                        painter = painter,
                        contentDescription = card.name,
                        modifier = Modifier.size(imageWidth, imageHeight),
                        contentScale = ContentScale.Fit,
                    )
                }
                render == null -> Text("Rendering…", color = Grey, fontSize = 16.sp)
                else -> Text("Couldn't render this code", color = Grey, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun StackScreen(
    name: String,
    cards: List<Card>,
    forceMaximumBrightness: Boolean,
    onLongCurrent: (Card) -> Unit,
    onBack: () -> Unit,
) {
    if (cards.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    KeepScreenBright(forceMaximumBrightness)
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { SimpleTopBar(name, onBack) }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val targetWidthPx = with(density) { (maxWidth - 32.dp).roundToPx() }
            val rendered = remember(name, cards, targetWidthPx) { mutableStateMapOf<String, BarcodeRender>() }

            LaunchedEffect(cards, targetWidthPx) {
                val uniqueCards = cards.distinctBy { it.id }
                suspend fun render(stackCard: Card) {
                    val bitmap = withContext(Dispatchers.Default) {
                        Barcodes.generateCached(stackCard.format, stackCard.data, targetWidthPx)
                    }
                    rendered[stackCard.id] = BarcodeRender(bitmap)
                }
                // Render the visible and next cards in parallel, then warm the rest
                // serially so switching is immediate without creating a CPU spike.
                launch { uniqueCards.firstOrNull()?.let { render(it) } }
                launch { uniqueCards.drop(1).forEach { render(it) } }
            }
            HardCutPager(pageCount = cards.size, showIndicator = false) { index, advance ->
                val card = cards[index]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BarcodePanel(
                        card = card,
                        onClick = advance,
                        onLongClick = { onLongCurrent(card) },
                        preRendered = rendered[card.id],
                        usePreRendered = true,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("${card.name} · ${index + 1}/${cards.size}", color = Grey, fontSize = 14.sp, fontWeight = FontWeight.Light)
                }
            }
        }
    }
}

@Composable
private fun CardScreen(card: Card, forceMaximumBrightness: Boolean, onLong: () -> Unit, onBack: () -> Unit) {
    KeepScreenBright(forceMaximumBrightness)
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { SimpleTopBar(card.name, onBack) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BarcodePanel(card = card, onClick = {}, onLongClick = onLong, longPressOnly = true)
        }
    }
}

@Composable
private fun rememberBarcodeRender(card: Card, targetWidthPx: Int): BarcodeRender? {
    var render by remember(card.id, card.data, card.format, targetWidthPx) { mutableStateOf<BarcodeRender?>(null) }
    LaunchedEffect(card.id, card.data, card.format, targetWidthPx) {
        val bitmap = withContext(Dispatchers.Default) {
            Barcodes.generateCached(card.format, card.data, targetWidthPx)
        }
        render = BarcodeRender(bitmap)
    }
    return render
}

@Composable
private fun KeepScreenBright(forceMaximumBrightness: Boolean) {
    val context = LocalContext.current
    DisposableEffect(forceMaximumBrightness) {
        val window = (context as? Activity)?.window
        window?.apply {
            if (forceMaximumBrightness) attributes = attributes.apply { screenBrightness = 1f }
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.apply {
                if (forceMaximumBrightness) {
                    attributes = attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
private fun ProtectSensitiveContent(enabled: Boolean) {
    // 2FA secrets and backup flows forbid screenshots. Pass barcodes remain
    // intentionally capturable because exporting a pass image is a core use case.
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@Composable
private fun BottomBar(mode: Mode, onSettings: () -> Unit, onAdd: () -> Unit, onAddLong: () -> Unit, onToggleMode: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(48.dp).then(tapModifier(onSettings, "Settings"))) { drawGear() }
        Canvas(modifier = Modifier.size(48.dp).then(tapLongModifier(onClick = onAdd, onLongClick = onAddLong, label = "Add"))) { drawPlus() }
        Canvas(modifier = Modifier.size(48.dp).then(tapLongModifier(onClick = onToggleMode, onLongClick = onToggleMode, label = if (mode == Mode.CARDS) "2FA codes" else "Cards"))) {
            if (mode == Mode.CARDS) drawBarcodeGlyph() else drawAsterisk()
        }
    }
}

private fun DrawScope.drawGear() {
    val s = size.minDimension
    val c = Offset(s / 2f, s / 2f)
    val body = s * 0.18f
    val hole = s * 0.085f
    val tw = s * 0.10f
    val th = s * 0.12f
    for (k in 0 until 8) {
        rotate(45f * k, c) {
            drawRect(color = White, topLeft = Offset(c.x - tw / 2f, c.y - body - th * 0.5f), size = Size(tw, th))
        }
    }
    drawCircle(White, body, c)
    drawCircle(Black, hole, c)
}

private fun DrawScope.drawPlus() {
    val s = size.minDimension
    val c = s / 2f
    val h = s * 0.27f
    val w = s * 0.085f
    drawLine(White, Offset(c - h, c), Offset(c + h, c), strokeWidth = w, cap = StrokeCap.Butt)
    drawLine(White, Offset(c, c - h), Offset(c, c + h), strokeWidth = w, cap = StrokeCap.Butt)
}

private fun DrawScope.drawBarcodeGlyph() {
    val s = size.minDimension
    val top = s * 0.24f
    val bot = s * 0.76f
    fun bar(x: Float, w: Float) = drawLine(White, Offset(x * s, top), Offset(x * s, bot), strokeWidth = w * s, cap = StrokeCap.Butt)
    bar(0.30f, 0.055f); bar(0.40f, 0.03f); bar(0.485f, 0.06f); bar(0.575f, 0.03f); bar(0.66f, 0.055f); bar(0.73f, 0.03f)
}

private fun DrawScope.drawAsterisk() {
    val s = size.minDimension
    val c = Offset(s / 2f, s / 2f)
    val r = s * 0.27f
    val w = s * 0.085f
    for (deg in listOf(90.0, 30.0, 150.0)) {
        val rad = Math.toRadians(deg)
        val dx = (r * cos(rad)).toFloat()
        val dy = (r * sin(rad)).toFloat()
        drawLine(White, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = w, cap = StrokeCap.Square)
    }
}
