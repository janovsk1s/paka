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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

/** A duplicate-confirmation route owns the submitted card even before it is saved. */
internal fun manualCardContentTransferred(
    saveAccepted: Boolean,
    pendingDuplicateCandidate: Card?,
    submitted: Card,
): Boolean = saveAccepted || pendingDuplicateCandidate == submitted

/**
 * Releases document bytes handed to duplicate confirmation if that handoff is
 * abandoned. The coordinator rechecks committed references before deleting,
 * so a document already used by the existing duplicate is always retained.
 */
private fun releaseUnreferencedPassContent(context: Context, card: Card) {
    StoreWriteCoordinator.deleteUnreferencedImports(
        context = context,
        pdfIds = setOfNotNull(card.pdfContent?.documentId),
        photoIds = card.photoContent?.pages.orEmpty().mapTo(hashSetOf(), PhotoPage::documentId),
    )
}

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
    private var criticalFlowActive = false

    fun setExternalFlowActive(active: Boolean) {
        externalFlowActive = active
    }

    fun setCriticalFlowActive(active: Boolean) {
        criticalFlowActive = active
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Prefs.language(newBase).applyTo(newBase))
    }

    internal fun switchLanguage(language: AppLanguage) {
        if (Prefs.language(this) == language) return
        Prefs.setLanguage(this, language)
        intent.putExtra(EXTRA_OPEN_DEVELOPER_ROUTE, DeveloperRoute.LANGUAGE.name)
        // The per-app locale service recreates the activity itself; recreate()
        // manually only on the pre-33 path where attachBaseContext does the work.
        if (!language.applyAsApplicationLocale(this)) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.reconcileApplicationLocale(
            context = this,
            selected = Prefs.language(this),
            hasExplicitSelection = Prefs.hasExplicitLanguage(this),
        )
        val openDeveloperRoute = intent.getStringExtra(EXTRA_OPEN_DEVELOPER_ROUTE)
            ?.let { saved -> runCatching { DeveloperRoute.valueOf(saved) }.getOrNull() }
        intent.removeExtra(EXTRA_OPEN_DEVELOPER_ROUTE)
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
        setContent { PakaApp(homeResetSignal, resumeSignal, openDeveloperRoute) }
    }

    override fun onResume() {
        super.onResume()
        resumeSignal++
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (externalFlowActive || criticalFlowActive) return
        if (StoreWriteCoordinator.isRestoreActive()) return
        if (Prefs.returnHome(this)) homeResetSignal++
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

    private companion object {
        const val EXTRA_OPEN_DEVELOPER_ROUTE = "com.paka.app.OPEN_DEVELOPER_ROUTE"
    }
}

internal fun Context.setPakaExternalFlowActive(active: Boolean) {
    (this as? MainActivity)?.setExternalFlowActive(active)
}

internal fun Context.setPakaCriticalFlowActive(active: Boolean) {
    (this as? MainActivity)?.setCriticalFlowActive(active)
}

@Composable
internal fun PakaApp(
    homeResetSignal: Int = 0,
    resumeSignal: Int = 0,
    openDeveloperRoute: DeveloperRoute? = null,
) {
    val context = LocalContext.current
    var officialFontEnabled by remember { mutableStateOf(Prefs.officialFont(context)) }
    ProvidePakaTypography(officialFontEnabled) {
        PakaAppContent(
            homeResetSignal = homeResetSignal,
            resumeSignal = resumeSignal,
            openDeveloperRoute = openDeveloperRoute,
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
    openDeveloperRoute: DeveloperRoute?,
    officialFontEnabled: Boolean,
    onOfficialFont: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var initialLoad by remember { mutableStateOf<InitialAppLoad?>(null) }
    LaunchedEffect(Unit) {
        StoreWriteCoordinator.awaitPendingWrites()
        initialLoad = withContext(Dispatchers.IO) {
            val recovery = StoreWriteCoordinator.recoverInterruptedRestore(context)
            val loadedCards = CardStore.load(context)
            val loadedCodes = SecureStore.loadAccounts(context)
            val recoveryWarning = when (recovery) {
                RestoreRecoveryOutcome.NONE -> null
                RestoreRecoveryOutcome.ROLLED_BACK -> resources.getString(R.string.restore_rolled_back_notice)
                RestoreRecoveryOutcome.COMMITTED -> resources.getString(R.string.restore_completed_notice)
                RestoreRecoveryOutcome.FAILED -> resources.getString(R.string.restore_failed_notice)
            }
            val cards = loadedCards.copy(
                warning = listOfNotNull(recoveryWarning, loadedCards.warning)
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() },
                writable = loadedCards.writable && recovery != RestoreRecoveryOutcome.FAILED,
            )
            val codes = loadedCodes.copy(writable = loadedCodes.writable && recovery != RestoreRecoveryOutcome.FAILED)
            if (cards.writable) {
                PdfStore.deleteOrphans(context, cards.value.mapNotNull { it.pdfContent?.documentId }.toSet())
                PhotoStore.deleteOrphans(context, cards.value.photoDocumentIds())
            }
            InitialAppLoad(cards = cards, codes = codes)
        }
    }

    val loaded = initialLoad
    if (loaded == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding(),
        ) {
            Text(
                stringResource(R.string.app_name),
                color = White,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.status_opening_paka),
                color = Grey,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    LoadedPakaApp(
        homeResetSignal = homeResetSignal,
        resumeSignal = resumeSignal,
        openDeveloperRoute = openDeveloperRoute,
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
    openDeveloperRoute: DeveloperRoute?,
    cardLoad: LoadOutcome<List<Card>>,
    codeLoad: LoadOutcome<List<OtpAccount>>,
    officialFontEnabled: Boolean,
    onOfficialFont: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var cards by remember { mutableStateOf(cardLoad.value) }
    var codes by remember { mutableStateOf(codeLoad.value) }
    var committedCards by remember { mutableStateOf(cardLoad.value) }
    var committedCodes by remember { mutableStateOf(codeLoad.value) }
    var cardsWritable by remember { mutableStateOf(cardLoad.writable) }
    var codesWritable by remember { mutableStateOf(codeLoad.writable) }
    var mode by remember { mutableStateOf(Mode.CARDS) }
    var showSettings by remember { mutableStateOf(openDeveloperRoute != null) }
    var showBackup by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(openDeveloperRoute != null) }
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
    var initialDevRoute by remember { mutableStateOf(openDeveloperRoute) }
    var showDev by remember { mutableStateOf(openDeveloperRoute != null) }
    var textSize by remember { mutableStateOf(Prefs.textSize(context)) }
    var vibrationEnabled by remember { mutableStateOf(Prefs.vibration(context)) }
    var returnHomeEnabled by remember { mutableStateOf(Prefs.returnHome(context)) }
    var autoLightEnabled by remember { mutableStateOf(Prefs.autoLight(context)) }
    var maxCodeBrightnessEnabled by remember { mutableStateOf(Prefs.maxCodeBrightness(context)) }
    var pageNumbersEnabled by remember { mutableStateOf(Prefs.pageNumbers(context)) }
    var lightGearEnabled by remember { mutableStateOf(Prefs.lightGear(context)) }
    var demoModeEnabled by remember { mutableStateOf(Prefs.demoMode(context)) }
    var demoContent by remember { mutableStateOf(DemoData.create(context)) }
    var onboardingComplete by remember { mutableStateOf(Prefs.onboardingComplete(context)) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var pendingClipboard by remember { mutableStateOf<PendingClipboard?>(null) }
    val foreground by rememberIsForeground()
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
            (pendingDuplicate as? PendingDuplicate.Pass)?.candidate?.let { candidate ->
                releaseUnreferencedPassContent(context, candidate)
            }
            pendingDuplicate = null
            selectedCard = null
            selectedStack = null
            detailCard = null
            scanning = false
            pendingScan = null
            manualCard = false
            manualCode = false
            initialDevRoute = null
            showDev = false
        }
    }
    // Gated on foreground so the per-second wake-up stops when Paka is
    // backgrounded; Compose would otherwise keep this loop alive while stopped.
    LaunchedEffect(codesVisible, foreground) {
        if (codesVisible && foreground) {
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
            Toast.makeText(context, R.string.cards_read_only, Toast.LENGTH_LONG).show()
            return false
        }
        val keptPdfIds = list.mapNotNull { it.pdfContent?.documentId }.toSet()
        val removedPdfIds = cards.mapNotNull { it.pdfContent?.documentId }.toSet() - keptPdfIds
        val keptPhotoIds = list.photoDocumentIds()
        val removedPhotoIds = cards.photoDocumentIds() - keptPhotoIds
        val write = StoreWriteCoordinator.saveCards(context, list, removedPdfIds, removedPhotoIds)
        if (write == null) {
            Toast.makeText(context, R.string.cards_queue_failed, Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, R.string.cards_save_failed, Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, R.string.codes_read_only, Toast.LENGTH_LONG).show()
            return false
        }
        val write = StoreWriteCoordinator.saveCodes(context, list)
        if (write == null) {
            Toast.makeText(context, R.string.codes_queue_failed, Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, R.string.codes_save_failed, Toast.LENGTH_LONG).show()
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
        else Toast.makeText(context, R.string.scan_camera_permission, Toast.LENGTH_LONG).show()
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
                kind = DuplicateKind.PASS,
                existingName = duplicate.existingName,
                onConfirm = {
                    if (addCard(duplicate.candidate, allowDuplicate = true)) pendingDuplicate = null
                },
                onBack = {
                    releaseUnreferencedPassContent(context, duplicate.candidate)
                    pendingDuplicate = null
                    manualCard = false
                    pendingScan = null
                },
            )
            return
        }
        is PendingDuplicate.Code -> {
            DuplicateConfirmScreen(
                kind = DuplicateKind.CODE,
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
            kind = if (managing == Mode.CARDS) ManageKind.PASSES else ManageKind.CODES,
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
            language = Prefs.language(context),
            onLanguage = { language -> (context as? MainActivity)?.switchLanguage(language) },
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
                    demoContent = DemoData.create(context)
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
                    if (enabled) R.string.demo_enabled else R.string.demo_disabled,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            initialRoute = initialDevRoute ?: DeveloperRoute.MENU,
            onBack = {
                initialDevRoute = null
                showDev = false
            },
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
                    restored.clearDocuments()
                    Toast.makeText(context, R.string.restore_storage_unhealthy, Toast.LENGTH_LONG).show()
                    false
                } else {
                    val outcome = StoreWriteCoordinator.restore(
                        context = context,
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
                            val diskState = withContext(Dispatchers.IO) {
                                CardStore.load(context) to SecureStore.loadAccounts(context)
                            }
                            cards = diskState.first.value
                            codes = diskState.second.value
                            committedCards = diskState.first.value
                            committedCodes = diskState.second.value
                            cardsWritable = diskState.first.writable
                            codesWritable = diskState.second.writable
                            Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_LONG).show()
                            false
                        }
                        RestoreOutcome.FAILED_PARTIAL -> {
                            val diskState = withContext(Dispatchers.IO) {
                                CardStore.load(context) to SecureStore.loadAccounts(context)
                            }
                            cards = diskState.first.value
                            codes = diskState.second.value
                            committedCards = diskState.first.value
                            committedCodes = diskState.second.value
                            cardsWritable = false
                            codesWritable = false
                            Toast.makeText(context, R.string.restore_interrupted, Toast.LENGTH_LONG).show()
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
            manageKind = if (mode == Mode.CARDS) ManageKind.PASSES else ManageKind.CODES,
            onReorder = { manageMode = mode },
            onBackup = {
                if (demoModeEnabled) {
                    Toast.makeText(context, R.string.backup_demo_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    showBackup = true
                }
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
            onSave = { card ->
                val saved = addCard(card)
                // A duplicate confirmation now owns the candidate even though
                // it has not been committed yet. Keep its imported blobs alive
                // until the user confirms or explicitly abandons the handoff.
                manualCardContentTransferred(
                    saveAccepted = saved,
                    pendingDuplicateCandidate = (pendingDuplicate as? PendingDuplicate.Pass)?.candidate,
                    submitted = card,
                )
            },
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
            title = stringResource(R.string.core_name),
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
                    val account = Totp.parseOtpauth(result.data, resources.getString(R.string.unknown_issuer))
                    if (account != null) addCode(account)
                    else Toast.makeText(context, R.string.scan_not_2fa, Toast.LENGTH_SHORT).show()
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
            Text(
                if (demoModeEnabled) stringResource(R.string.home_demo_title) else stringResource(R.string.app_name),
                color = White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                Mode.CARDS ->
                    if (activeCards.isEmpty()) EmptyHint(stringResource(R.string.home_add_card_hint))
                    else CardsList(
                        entries = buildEntries(activeCards),
                        textSize = textSize,
                        onOpenCard = { selectedCard = it },
                        onOpenStack = { selectedStack = it },
                    )
                Mode.CODES ->
                    if (activeCodes.isEmpty()) EmptyHint(stringResource(R.string.home_add_code_hint))
                    else CodesList(
                        accounts = activeCodes,
                        nowMs = nowMs,
                        textSize = textSize,
                        onCopy = { code ->
                            if (code.any { it == '-' }) return@CodesList
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val data = ClipData.newPlainText(resources.getString(R.string.clipboard_2fa_label), code)
                            data.description.extras = PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            clip.setPrimaryClip(data)
                            pendingClipboard = PendingClipboard(
                                value = code,
                                expiresAtMs = System.currentTimeMillis() + 30_000L,
                            )
                            Toast.makeText(context, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
                        },
                    )
            }
        }

        val storageNotice = when {
            demoModeEnabled -> null
            mode == Mode.CARDS && !cardsWritable -> R.string.cards_read_only
            mode == Mode.CODES && !codesWritable -> R.string.codes_read_only
            else -> null
        }
        if (storageNotice != null) {
            Text(
                stringResource(storageNotice),
                color = Grey,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.fillMaxWidth().padding(end = 14.dp, bottom = 4.dp),
            )
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
            Text(
                stringResource(R.string.onboarding_title),
                color = White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, end = 14.dp, bottom = 8.dp)) {
            OnboardingRow(weight = 1f) {
                Text(stringResource(R.string.app_name), color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
            }
            OnboardingRow(weight = 1f) {
                Text(
                    stringResource(R.string.onboarding_summary),
                    color = White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
            OnboardingRow(weight = 1f) {
                Text(
                    stringResource(R.string.onboarding_scan),
                    color = White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
            OnboardingRow(weight = 1f) {
                Text(
                    stringResource(R.string.onboarding_privacy),
                    color = White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
            OnboardingRow(weight = 1f) {
                Text(
                    stringResource(R.string.onboarding_start),
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
    val settingsLabel = stringResource(R.string.core_settings)
    val addLabel = stringResource(R.string.core_add)
    val manualEntryLabel = stringResource(R.string.core_manual_entry)
    val modeLabel = stringResource(
        if (mode == Mode.CARDS) R.string.core_show_2fa_codes else R.string.core_show_passes,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lightGear) {
            Box(
                modifier = Modifier.size(48.dp).then(tapModifier(onSettings, settingsLabel)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_light_settings_white),
                    contentDescription = null,
                    modifier = Modifier.size(27.dp),
                )
            }
        } else {
            Canvas(modifier = Modifier.size(48.dp).then(tapModifier(onSettings, settingsLabel))) { drawGear() }
        }
        Canvas(
            modifier = Modifier.size(48.dp).then(
                tapLongModifier(
                    onClick = onAdd,
                    onLongClick = onAddLong,
                    label = addLabel,
                    longClickLabel = manualEntryLabel,
                ),
            ),
        ) { drawPlus() }
        Canvas(
            modifier = Modifier.size(48.dp).then(tapModifier(onToggleMode, modeLabel)),
        ) {
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
