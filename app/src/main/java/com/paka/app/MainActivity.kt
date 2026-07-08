package com.paka.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class Mode { CARDS, CODES }
private enum class ScanMode { CARD, CODE }

private sealed interface PendingDuplicate {
    data class Pass(val candidate: Card, val existingName: String) : PendingDuplicate
    data class Code(val candidate: OtpAccount, val existingName: String) : PendingDuplicate
}

private data class InitialAppLoad(
    val cards: LoadOutcome<List<Card>>,
    val codes: LoadOutcome<List<OtpAccount>>,
)
private data class PendingClipboard(val value: String, val expiresAtMs: Long)

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
    private var externalFlowActive = false

    fun setExternalFlowActive(active: Boolean) {
        externalFlowActive = active
    }

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
        if (!externalFlowActive && Prefs.returnHome(this)) homeResetSignal++
    }

    override fun onStop() {
        super.onStop()
        // Decoded photo bitmaps live only while Paka is in the foreground.
        PhotoStore.trimMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        PhotoStore.trimMemory()
    }
}

internal fun Context.setPakaExternalFlowActive(active: Boolean) {
    (this as? MainActivity)?.setExternalFlowActive(active)
}

@Composable
fun PakaApp(homeResetSignal: Int = 0, resumeSignal: Int = 0) {
    val context = LocalContext.current
    var officialFontEnabled by remember { mutableStateOf(Prefs.officialFont(context)) }
    ProvidePakaTypography(officialFontEnabled) {
        PakaAppContent(
            homeResetSignal = homeResetSignal,
            resumeSignal = resumeSignal,
            officialFontEnabled = officialFontEnabled,
            onOfficialFont = { enabled ->
                officialFontEnabled = enabled
                Prefs.setOfficialFont(context, enabled)
            },
        )
    }
}

@Composable
private fun PakaAppContent(
    homeResetSignal: Int,
    resumeSignal: Int,
    officialFontEnabled: Boolean,
    onOfficialFont: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var initialLoad by remember { mutableStateOf<InitialAppLoad?>(null) }
    LaunchedEffect(Unit) {
        StoreWriteCoordinator.awaitPendingWrites()
        initialLoad = withContext(Dispatchers.IO) {
            val cards = CardStore.load(context)
            if (cards.writable) {
                PdfStore.deleteOrphans(context, cards.value.mapNotNull { it.pdfContent?.documentId }.toSet())
                PhotoStore.deleteOrphans(context, cards.value.photoDocumentIds())
            }
            InitialAppLoad(cards = cards, codes = SecureStore.loadAccounts(context))
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
    LoadedPakaApp(
        homeResetSignal = homeResetSignal,
        resumeSignal = resumeSignal,
        cardLoad = loaded.cards,
        codeLoad = loaded.codes,
        officialFontEnabled = officialFontEnabled,
        onOfficialFont = onOfficialFont,
    )
}

@Composable
private fun LoadedPakaApp(
    homeResetSignal: Int,
    resumeSignal: Int,
    cardLoad: LoadOutcome<List<Card>>,
    codeLoad: LoadOutcome<List<OtpAccount>>,
    officialFontEnabled: Boolean,
    onOfficialFont: (Boolean) -> Unit,
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
    var mode by remember { mutableStateOf(Mode.CARDS) }
    var showSettings by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var manageMode by remember { mutableStateOf<Mode?>(null) }
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
    var pageNumbersEnabled by remember { mutableStateOf(Prefs.pageNumbers(context)) }
    var lightGearEnabled by remember { mutableStateOf(Prefs.lightGear(context)) }
    var demoModeEnabled by remember { mutableStateOf(Prefs.demoMode(context)) }
    var demoContent by remember { mutableStateOf(DemoData.create()) }
    var onboardingComplete by remember { mutableStateOf(Prefs.onboardingComplete(context)) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var pendingClipboard by remember { mutableStateOf<PendingClipboard?>(null) }
    val scope = rememberCoroutineScope()
    val activeCards = if (demoModeEnabled) demoContent.cards else cards
    val activeCodes = if (demoModeEnabled) demoContent.accounts else codes
    val homeVisible =
        onboardingComplete && !showSettings && !showBackup && !showAbout && manageMode == null && pendingDuplicate == null && !showDev &&
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
    LaunchedEffect(homeVisible, mode, activeCards, passWidthPx) {
        if (homeVisible && mode == Mode.CARDS) {
            delay(250)
            activeCards.take(6).forEach { card ->
                card.barcodeContent?.let { barcode ->
                    withContext(Dispatchers.Default) {
                        Barcodes.generateCached(barcode.format, barcode.data, passWidthPx)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        cardLoad.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        codeLoad.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
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
        if (demoModeEnabled) {
            demoContent = demoContent.copy(cards = list)
            return true
        }
        if (!cardsWritable) {
            Toast.makeText(context, "Cards are read-only until storage is recovered", Toast.LENGTH_LONG).show()
            return false
        }
        val keptPdfIds = list.mapNotNull { it.pdfContent?.documentId }.toSet()
        val removedPdfIds = cards.mapNotNull { it.pdfContent?.documentId }.toSet() - keptPdfIds
        val keptPhotoIds = list.photoDocumentIds()
        val removedPhotoIds = cards.photoDocumentIds() - keptPhotoIds
        val write = StoreWriteCoordinator.saveCards(context, list, removedPdfIds, removedPhotoIds)
        if (write == null) {
            Toast.makeText(context, "Cards could not be queued for saving", Toast.LENGTH_LONG).show()
            return false
        }
        cards = list
        scope.launch {
            when (write.await()) {
                StoreWriteStatus.SAVED -> committedCards = list
                StoreWriteStatus.SUPERSEDED -> Unit
                StoreWriteStatus.FAILED -> {
                    cards = committedCards
                    cardsWritable = false
                    Toast.makeText(context, "Cards could not be saved", Toast.LENGTH_LONG).show()
                }
            }
        }
        return true
    }

    fun saveCodes(list: List<OtpAccount>): Boolean {
        if (demoModeEnabled) {
            demoContent = demoContent.copy(accounts = list)
            return true
        }
        if (!codesWritable) {
            Toast.makeText(context, "2FA accounts are read-only until storage is recovered", Toast.LENGTH_LONG).show()
            return false
        }
        val write = StoreWriteCoordinator.saveCodes(context, list)
        if (write == null) {
            Toast.makeText(context, "2FA accounts could not be queued for saving", Toast.LENGTH_LONG).show()
            return false
        }
        codes = list
        scope.launch {
            when (write.await()) {
                StoreWriteStatus.SAVED -> committedCodes = list
                StoreWriteStatus.SUPERSEDED -> Unit
                StoreWriteStatus.FAILED -> {
                    codes = committedCodes
                    codesWritable = false
                    Toast.makeText(context, "2FA accounts could not be saved", Toast.LENGTH_LONG).show()
                }
            }
        }
        return true
    }

    val updateCard: (Card) -> Boolean = { u -> saveCards(activeCards.map { if (it.id == u.id) u else it }) }
    fun addCard(card: Card, allowDuplicate: Boolean = false): Boolean {
        if (!allowDuplicate) {
            activeCards.firstOrNull { it.content == card.content }?.let { existing ->
                pendingDuplicate = PendingDuplicate.Pass(card, existing.name)
                return false
            }
        }
        if (!saveCards(activeCards + card)) return false
        manualCard = false
        pendingScan = null
        selectedCard = card
        return true
    }
    fun addCode(account: OtpAccount, allowDuplicate: Boolean = false): Boolean {
        if (!allowDuplicate) {
            activeCodes.firstOrNull { it.duplicateKey() == account.duplicateKey() }?.let { existing ->
                pendingDuplicate = PendingDuplicate.Code(account, existing.title())
                return false
            }
        }
        if (!saveCodes(activeCodes + account)) return false
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

    ProtectSensitiveContent(
        showBackup || manualCode || (scanning && scanMode == ScanMode.CODE) || (mode == Mode.CODES && !demoModeEnabled),
    )

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

    val managing = manageMode
    if (managing != null) {
        val rows = if (managing == Mode.CARDS) activeCards.map { ManageRow(it.id, it.name) }
        else activeCodes.map { ManageRow(it.id, it.title()) }
        ManageScreen(
            rows = rows,
            onUp = { id ->
                if (managing == Mode.CARDS) saveCards(activeCards.moved(activeCards.indexOfFirst { it.id == id }, true))
                else saveCodes(activeCodes.moved(activeCodes.indexOfFirst { it.id == id }, true))
            },
            onDown = { id ->
                if (managing == Mode.CARDS) saveCards(activeCards.moved(activeCards.indexOfFirst { it.id == id }, false))
                else saveCodes(activeCodes.moved(activeCodes.indexOfFirst { it.id == id }, false))
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
            pageNumbersEnabled = pageNumbersEnabled,
            onPageNumbers = { enabled ->
                pageNumbersEnabled = enabled
                Prefs.setPageNumbers(context, enabled)
            },
            officialFontEnabled = officialFontEnabled,
            onOfficialFont = onOfficialFont,
            lightGearEnabled = lightGearEnabled,
            onLightGear = { enabled ->
                lightGearEnabled = enabled
                Prefs.setLightGear(context, enabled)
            },
            demoModeEnabled = demoModeEnabled,
            onDemoMode = { enabled ->
                if (enabled) {
                    demoContent = DemoData.create()
                    pendingClipboard?.let { pending ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (current == pending.value) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                    pendingClipboard = null
                }
                demoModeEnabled = enabled
                Prefs.setDemoMode(context, enabled)
                Toast.makeText(
                    context,
                    if (enabled) "temporary demo data enabled" else "real data restored",
                    Toast.LENGTH_SHORT,
                ).show()
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
                    val outcome = StoreWriteCoordinator.restore(
                        context = context,
                        oldCards = oldCards,
                        oldCodes = oldCodes,
                        restored = restored,
                    ).await()
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
            onBackup = {
                if (demoModeEnabled) Toast.makeText(context, "backup is unavailable in demo mode", Toast.LENGTH_SHORT).show()
                else showBackup = true
            },
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
            documentImportsEnabled = !demoModeEnabled,
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
        val fresh = activeCards.firstOrNull { it.id == detail.id } ?: detail
        CardDetail(
            card = fresh,
            onUpdate = updateCard,
            onDelete = { saveCards(activeCards.filter { it.id != fresh.id }) },
            stackMembers = { stackName ->
                activeCards.filter { it.stack == stackName }.map { ManageRow(it.id, it.name) }
            },
            onStackMove = { id, up -> saveCards(activeCards.movedWithinStack(id, up)) },
            onBack = { detailCard = null },
        )
        return
    }

    val stackName = selectedStack
    if (stackName != null) {
        val stackCards = activeCards.filter { it.stack == stackName }
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
        val fresh = activeCards.firstOrNull { it.id == card.id } ?: card
        BackHandler { selectedCard = null }
        when (val content = fresh.content) {
            is PassContent.Barcode -> CardScreen(
                card = fresh,
                forceMaximumBrightness = maxCodeBrightnessEnabled,
                onLong = { detailCard = fresh },
                onBack = { selectedCard = null },
            )
            is PassContent.Pdf -> PdfScreen(
                card = fresh,
                content = content,
                forceMaximumBrightness = maxCodeBrightnessEnabled,
                onLong = { detailCard = fresh },
                onBack = { selectedCard = null },
            )
            is PassContent.Photos -> PhotoScreen(
                card = fresh,
                content = content,
                forceMaximumBrightness = maxCodeBrightnessEnabled,
                onLong = { detailCard = fresh },
                onBack = { selectedCard = null },
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Text(if (demoModeEnabled) "Paka · demo" else "Paka", color = White, fontSize = 16.sp, fontWeight = FontWeight.Normal)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                Mode.CARDS ->
                    if (activeCards.isEmpty()) EmptyHint("tap + to add a card")
                    else CardsList(
                        entries = buildEntries(activeCards),
                        textSize = textSize,
                        onOpenCard = { selectedCard = it },
                        onOpenStack = { selectedStack = it },
                    )
                Mode.CODES ->
                    if (activeCodes.isEmpty()) EmptyHint("tap + to add a code")
                    else CodesList(
                        accounts = activeCodes,
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
            lightGear = lightGearEnabled,
            onSettings = { showSettings = true },
            onAdd = { scanMode = if (mode == Mode.CARDS) ScanMode.CARD else ScanMode.CODE; startScan() },
            onAddLong = { if (mode == Mode.CARDS) manualCard = true else manualCode = true },
            onToggleMode = { mode = if (mode == Mode.CARDS) Mode.CODES else Mode.CARDS },
        )
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
                Text("Swipe between pages. Open a pass, then long-press it for details. Data stays encrypted on this phone.", color = White, fontSize = 20.sp, fontWeight = FontWeight.Normal)
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
private fun BottomBar(
    mode: Mode,
    lightGear: Boolean,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onAddLong: () -> Unit,
    onToggleMode: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lightGear) {
            Box(
                modifier = Modifier.size(48.dp).then(tapModifier(onSettings, "Settings")),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_light_settings_white),
                    contentDescription = null,
                    modifier = Modifier.size(27.dp),
                )
            }
        } else {
            Canvas(modifier = Modifier.size(48.dp).then(tapModifier(onSettings, "Settings"))) { drawGear() }
        }
        Canvas(modifier = Modifier.size(48.dp).then(tapLongModifier(onClick = onAdd, onLongClick = onAddLong, label = "Add"))) { drawPlus() }
        Canvas(modifier = Modifier.size(48.dp).then(tapLongModifier(onClick = onToggleMode, onLongClick = onToggleMode, label = if (mode == Mode.CARDS) "2FA codes" else "Cards"))) {
            if (mode == Mode.CARDS) drawBarcodeGlyph() else drawAsterisk()
        }
    }
}

private fun DrawScope.drawGear() {
    val s = size.minDimension
    val toothWidth = (s * GEAR_TOOTH_WIDTH).roundToInt().toFloat()
    val toothHeight = (s * GEAR_TOOTH_HEIGHT).roundToInt().toFloat()
    val center = pixelAlignedCenter(s / 2f, toothWidth)
    val c = Offset(center, center)
    val body = (s * GEAR_BODY_RADIUS).roundToInt().toFloat()
    val hole = (s * GEAR_HOLE_RADIUS).roundToInt().toFloat()
    val outerRadius = (s * BOTTOM_PLUS_HALF_LENGTH).roundToInt().toFloat()
    for (k in 0 until GEAR_TOOTH_COUNT) {
        rotate(GEAR_TOOTH_STEP_DEGREES * k, c) {
            drawRect(
                color = White,
                topLeft = Offset(
                    pixelAlignedCenter(c.x, toothWidth) - toothWidth / 2f,
                    c.y - outerRadius,
                ),
                size = Size(toothWidth, toothHeight),
            )
        }
    }
    drawCircle(White, body, c)
    drawCircle(Black, hole, c)
}

private fun DrawScope.drawPlus() {
    val s = size.minDimension
    val halfLength = (s * BOTTOM_PLUS_HALF_LENGTH).roundToInt().toFloat()
    val stroke = pixelAlignedStroke(s * BOTTOM_PLUS_STROKE)
    val center = pixelAlignedCenter(s / 2f, stroke)
    drawLine(
        White,
        Offset(center - halfLength, center),
        Offset(center + halfLength, center),
        strokeWidth = stroke,
        cap = StrokeCap.Butt,
    )
    drawLine(
        White,
        Offset(center, center - halfLength),
        Offset(center, center + halfLength),
        strokeWidth = stroke,
        cap = StrokeCap.Butt,
    )
}

private fun DrawScope.drawBarcodeGlyph() {
    val s = size.minDimension
    val center = pixelAlignedCenter(s / 2f, pixelAlignedStroke(s * BOTTOM_PLUS_STROKE))
    val halfLength = (s * BOTTOM_PLUS_HALF_LENGTH).roundToInt().toFloat()
    val top = center - halfLength
    val bottom = center + halfLength
    fun bar(x: Float, width: Float) =
        drawLine(
            White,
            Offset(pixelAlignedCenter((x + BOTTOM_BARCODE_CENTERING_OFFSET) * s, width * s), top),
            Offset(pixelAlignedCenter((x + BOTTOM_BARCODE_CENTERING_OFFSET) * s, width * s), bottom),
            strokeWidth = pixelAlignedStroke(width * s),
            cap = StrokeCap.Butt,
        )
    BOTTOM_BARCODE_BARS.forEach { (x, width) -> bar(x, width) }
}

private fun pixelAlignedStroke(rawWidth: Float): Float =
    rawWidth.roundToInt().coerceAtLeast(MIN_STROKE_PX).toFloat()

private fun pixelAlignedCenter(rawCenter: Float, rawWidth: Float): Float {
    val stroke = pixelAlignedStroke(rawWidth).toInt()
    val rounded = rawCenter.roundToInt().toFloat()
    return if (stroke % ODD_STROKE_MODULUS == 0) rounded else rounded + HALF_PIXEL
}

private fun DrawScope.drawAsterisk() {
    val s = size.minDimension
    val stroke = pixelAlignedStroke(s * BOTTOM_PLUS_STROKE)
    val center = pixelAlignedCenter(s / 2f, stroke)
    val c = Offset(center, center)
    val radius = (s * BOTTOM_ASTERISK_RADIUS).roundToInt().toFloat()
    for (deg in BOTTOM_ASTERISK_ANGLES) {
        val rad = Math.toRadians(deg)
        val dx = (radius * cos(rad)).toFloat()
        val dy = (radius * sin(rad)).toFloat()
        drawLine(
            White,
            Offset(pixelAlignedCenter(c.x - dx, stroke), pixelAlignedCenter(c.y - dy, stroke)),
            Offset(pixelAlignedCenter(c.x + dx, stroke), pixelAlignedCenter(c.y + dy, stroke)),
            strokeWidth = stroke,
            cap = StrokeCap.Square,
        )
    }
}

private const val MIN_STROKE_PX = 1
private const val ODD_STROKE_MODULUS = 2
private const val HALF_PIXEL = 0.5f
private const val GEAR_TOOTH_COUNT = 8
private const val GEAR_TOOTH_STEP_DEGREES = 45f
private const val GEAR_BODY_RADIUS = 0.205f
private const val GEAR_HOLE_RADIUS = 0.085f
private const val GEAR_TOOTH_WIDTH = 0.10f
private const val GEAR_TOOTH_HEIGHT = 0.12f
private const val BOTTOM_PLUS_HALF_LENGTH = 0.27f
private const val BOTTOM_PLUS_STROKE = 0.085f
private const val BOTTOM_ASTERISK_RADIUS = 0.27f
private const val BOTTOM_ASTERISK_VERTICAL_DEGREES = 90.0
private const val BOTTOM_ASTERISK_RISING_DEGREES = 30.0
private const val BOTTOM_ASTERISK_FALLING_DEGREES = 150.0
private const val BOTTOM_BARCODE_CENTERING_OFFSET = -0.015f
private const val BOTTOM_BARCODE_X_1 = 0.30f
private const val BOTTOM_BARCODE_X_2 = 0.40f
private const val BOTTOM_BARCODE_X_3 = 0.485f
private const val BOTTOM_BARCODE_X_4 = 0.575f
private const val BOTTOM_BARCODE_X_5 = 0.66f
private const val BOTTOM_BARCODE_X_6 = 0.73f
private const val BOTTOM_BARCODE_THIN = 0.03f
private const val BOTTOM_BARCODE_THICK = 0.055f
private const val BOTTOM_BARCODE_WIDE = 0.06f
private val BOTTOM_BARCODE_BARS = listOf(
    BOTTOM_BARCODE_X_1 to BOTTOM_BARCODE_THICK,
    BOTTOM_BARCODE_X_2 to BOTTOM_BARCODE_THIN,
    BOTTOM_BARCODE_X_3 to BOTTOM_BARCODE_WIDE,
    BOTTOM_BARCODE_X_4 to BOTTOM_BARCODE_THIN,
    BOTTOM_BARCODE_X_5 to BOTTOM_BARCODE_THICK,
    BOTTOM_BARCODE_X_6 to BOTTOM_BARCODE_THIN,
)
private val BOTTOM_ASTERISK_ANGLES = listOf(
    BOTTOM_ASTERISK_VERTICAL_DEGREES,
    BOTTOM_ASTERISK_RISING_DEGREES,
    BOTTOM_ASTERISK_FALLING_DEGREES,
)
