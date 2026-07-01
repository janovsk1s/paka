package com.paka.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.sin

private enum class Mode { CARDS, CODES }
private enum class ScanMode { CARD, CODE }

private sealed interface Entry
private data class SingleEntry(val card: Card) : Entry
private data class StackEntry(val name: String, val cards: List<Card>) : Entry

private data class ManageRow(val id: String, val name: String)
private data class RenameTarget(val id: String, val current: String, val isCard: Boolean)
private data class BarcodeRender(val bitmap: android.graphics.Bitmap?)

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PakaApp() }
    }
}

@Composable
fun PakaApp() {
    val context = LocalContext.current
    val cardLoad = remember { CardStore.load(context) }
    val codeLoad = remember { SecureStore.loadAccounts(context) }
    var cards by remember { mutableStateOf(cardLoad.value) }
    var codes by remember { mutableStateOf(codeLoad.value) }
    var cardsWritable by remember { mutableStateOf(cardLoad.writable) }
    var codesWritable by remember { mutableStateOf(codeLoad.writable) }
    var mode by remember { mutableStateOf(Mode.CARDS) }
    var showSettings by remember { mutableStateOf(false) }
    var manageMode by remember { mutableStateOf<Mode?>(null) }
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
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
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val codesVisible = mode == Mode.CODES &&
        !showSettings && manageMode == null && renameTarget == null && !showDev &&
        !manualCard && !manualCode && pendingScan == null && !scanning &&
        detailCard == null && selectedStack == null && selectedCard == null
    LaunchedEffect(codesVisible) {
        if (codesVisible) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    LaunchedEffect(Unit) {
        cardLoad.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        codeLoad.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    fun saveCards(list: List<Card>): Boolean {
        if (!cardsWritable) {
            Toast.makeText(context, "Cards are read-only until storage is recovered", Toast.LENGTH_LONG).show()
            return false
        }
        return CardStore.save(context, list).fold(
            onSuccess = { cards = list; true },
            onFailure = {
                cardsWritable = false
                Toast.makeText(context, "Cards could not be saved", Toast.LENGTH_LONG).show()
                false
            },
        )
    }

    fun saveCodes(list: List<OtpAccount>): Boolean {
        if (!codesWritable) {
            Toast.makeText(context, "2FA accounts are read-only until storage is recovered", Toast.LENGTH_LONG).show()
            return false
        }
        return SecureStore.saveAccounts(context, list).fold(
            onSuccess = { codes = list; true },
            onFailure = {
                codesWritable = false
                Toast.makeText(context, "2FA accounts could not be saved", Toast.LENGTH_LONG).show()
                false
            },
        )
    }

    val updateCard: (Card) -> Boolean = { u -> saveCards(cards.map { if (it.id == u.id) u else it }) }
    fun addCode(account: OtpAccount): Boolean {
        if (!saveCodes(codes + account)) return false
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

    ProtectSensitiveContent(mode == Mode.CODES || manualCode || (scanning && scanMode == ScanMode.CODE))

    // ---- routing (topmost overlay wins) ----

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
        DevScreen(textSize = textSize, onTextSize = { textSize = it; Prefs.setTextSize(context, it) }, onBack = { showDev = false })
        return
    }

    if (showSettings) {
        SettingsScreen(
            onReorder = { showSettings = false; manageMode = mode },
            vibrationEnabled = vibrationEnabled,
            onVibration = { enabled ->
                vibrationEnabled = enabled
                Prefs.setVibration(context, enabled)
            },
            onDev = { showDev = true },
            onBack = { showSettings = false },
        )
        return
    }

    if (manualCard) {
        ManualCardScreen(
            onSave = { card ->
                if (saveCards(cards + card)) {
                    manualCard = false
                    selectedCard = card
                }
            },
            onBack = { manualCard = false },
        )
        return
    }

    if (manualCode) {
        ManualCodeScreen(onSave = { if (addCode(it)) manualCode = false }, onBack = { manualCode = false })
        return
    }

    val pending = pendingScan
    if (pending != null) {
        TextEntryScreen(
            title = "name",
            initial = "",
            onSave = { name ->
                val card = Card(name = name, data = pending.data, format = pending.format)
                if (saveCards(cards + card)) {
                    pendingScan = null
                    selectedCard = card
                }
            },
            onBack = { pendingScan = null },
        )
        return
    }

    if (scanning) {
        BackHandler { scanning = false }
        ScanScreen(
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
        StackScreen(name = stackName, cards = stackCards, onLongCurrent = { detailCard = it }, onBack = { selectedStack = null })
        return
    }

    val card = selectedCard
    if (card != null) {
        val fresh = cards.firstOrNull { it.id == card.id } ?: card
        BackHandler { selectedCard = null }
        CardScreen(card = fresh, onLong = { detailCard = fresh }, onBack = { selectedCard = null })
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Text("paka", color = White, fontSize = 16.sp, fontWeight = FontWeight.Normal)
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
                            Toast.makeText(context, "copied", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                delay(30_000)
                                val current = clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (current == code) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clip.clearPrimaryClip()
                                    else clip.setPrimaryClip(ClipData.newPlainText("", ""))
                                }
                            }
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
        BackArrow(modifier = Modifier.align(Alignment.CenterStart), onBack = onBack)
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Normal)
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
            modifier = Modifier.fillMaxSize().verticalScroll(state).padding(top = topPadding, end = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        VerticalScrollbar(state, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun rememberHapticScrollState(): ScrollState {
    val state = rememberScrollState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) { 48.dp.roundToPx().coerceAtLeast(1) }
    LaunchedEffect(state, stepPx) {
        snapshotFlow { state.value / stepPx }
            .distinctUntilChanged()
            .drop(1)
            .collect { performPakaHaptic(context, haptics) }
    }
    return state
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
    HardCutPager(pageCount = pages.size) { currentPage ->
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
    content: @Composable (Int) -> Unit,
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
        content(currentPage)
        if (pageCount > 1) {
            PageIndicator(
                page = currentPage,
                pageCount = pageCount,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun PageIndicator(page: Int, pageCount: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.offset(x = 18.dp).fillMaxHeight().width(6.dp)) {
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

@Composable
private fun SettingsScreen(
    onReorder: () -> Unit,
    vibrationEnabled: Boolean,
    onVibration: (Boolean) -> Unit,
    onDev: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var aboutTaps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("settings", onBack)
        ScrollList(topPadding = 44.dp, spacing = 36.dp) {
            Text("reorder", color = White, fontSize = 40.sp, fontWeight = FontWeight.Normal, modifier = Modifier.fillMaxWidth().then(tapModifier(onReorder)))
            Row(
                modifier = Modifier.fillMaxWidth().then(
                    tapModifier {
                        val enabled = !vibrationEnabled
                        onVibration(enabled)
                        if (enabled) performPakaHaptic(context, haptics)
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("vibration", color = White, fontSize = 40.sp, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
                Text(if (vibrationEnabled) "on" else "off", color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light)
            }
            Text(
                "about",
                color = White, fontSize = 40.sp, fontWeight = FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().then(
                    tapModifier {
                        val now = System.currentTimeMillis()
                        aboutTaps = if (now - lastTap < 600) aboutTaps + 1 else 1
                        lastTap = now
                        if (aboutTaps >= 3) { aboutTaps = 0; onDev() }
                    },
                ),
            )
        }
    }
}

@Composable
private fun DevScreen(textSize: Float, onTextSize: (Float) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("developer", onBack)
        Column(modifier = Modifier.fillMaxSize().padding(top = 44.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            FieldLabel("list text size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("−", color = White, fontSize = 44.sp, fontWeight = FontWeight.Normal, modifier = tapModifier { onTextSize((textSize - 1f).coerceAtLeast(16f)) })
                Text("${textSize.toInt()} sp", color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("+", color = White, fontSize = 44.sp, fontWeight = FontWeight.Normal, modifier = tapModifier { onTextSize((textSize + 1f).coerceAtMost(64f)) })
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("preview")
            Text("KlimaTicket", color = White, fontSize = textSize.sp, fontWeight = FontWeight.Normal)
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
    val deleting = pendingDelete
    if (deleting != null) {
        ConfirmDeleteScreen(
            name = deleting.name,
            onConfirm = { onDelete(deleting.id); pendingDelete = null },
            onBack = { pendingDelete = null },
        )
        return
    }
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("manage", onBack)
        ScrollList(topPadding = 20.dp, spacing = 20.dp) {
            rows.forEachIndexed { index, row ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(row.name, color = White, fontSize = 24.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ManageAction("rename", "Rename ${row.name}", Modifier.weight(1.35f)) { onRename(row.id) }
                        ManageAction("up", "Move ${row.name} up", Modifier.weight(0.75f), enabled = index > 0) { onUp(row.id) }
                        ManageAction("down", "Move ${row.name} down", Modifier.weight(0.9f), enabled = index < rows.lastIndex) { onDown(row.id) }
                        ManageAction("delete", "Delete ${row.name}", Modifier.weight(1.2f), destructive = true) { pendingDelete = row }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageAction(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Grey.copy(alpha = 0.45f)
        destructive -> Grey
        else -> White
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .then(if (enabled) tapModifier(onClick, description) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun ConfirmDeleteScreen(name: String, onConfirm: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("delete $name?", color = White, fontSize = 34.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(44.dp))
        Text("delete", color = White, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onConfirm)))
        Spacer(Modifier.height(28.dp))
        Text("cancel", color = Grey, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onBack)))
    }
}

@Composable
private fun ManualCardScreen(onSave: (Card) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var data by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(PakaFormat.QR) }
    val validationError = data.takeIf { it.isNotBlank() }?.let { Barcodes.validationError(format, it.trim()) }
    val canSave = name.isNotBlank() && data.isNotBlank() && validationError == null
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("card", onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberHapticScrollState()).padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EditField("name", name, { name = it }, "e.g. Billa")
            EditField("data", data, { data = it }, "code content")
            FieldLabel("format")
            Column {
                for (f in MANUAL_FORMATS) {
                    Text(
                        text = f.label(),
                        color = if (f == format) White else Grey,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.fillMaxWidth().then(tapModifier { format = f }).padding(vertical = 8.dp),
                    )
                }
            }
            validationError?.let { Text(it, color = Grey, fontSize = 14.sp) }
        }
        Text(
            text = "save",
            color = if (canSave) White else Grey,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(vertical = 18.dp).then(tapModifier { if (canSave) onSave(Card(name = name.trim(), data = data.trim(), format = format)) }),
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
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberHapticScrollState()).padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            EditField("name", issuer, { issuer = it }, "e.g. GitHub")
            EditField("account", account, { account = it }, "optional")
            EditField("secret", secret, { secret = it }, "base32 key", visualTransformation = PasswordVisualTransformation())
            validationError?.let { Text(it, color = Grey, fontSize = 14.sp) }
        }
        Text(
            text = "save",
            color = if (canSave) White else Grey,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(vertical = 18.dp).then(tapModifier { if (canSave) onSave(draft) }),
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
                    textStyle = TextStyle(color = White, fontSize = 40.sp, fontWeight = FontWeight.Normal),
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
        HardCutPager(pageCount = 2, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
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
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(White)
            .then(tapLongModifier(onClick = onClick, onLongClick = onLongClick, label = card.name))
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
private fun StackScreen(name: String, cards: List<Card>, onLongCurrent: (Card) -> Unit, onBack: () -> Unit) {
    if (cards.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    KeepScreenBright()
    var index by remember(name) { mutableIntStateOf(0) }
    val i = index % cards.size
    val card = cards[i]

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
                        Barcodes.generate(stackCard.format, stackCard.data, targetWidthPx)
                    }
                    rendered[stackCard.id] = BarcodeRender(bitmap)
                }
                // Render the visible and next cards in parallel, then warm the rest
                // serially so switching is immediate without creating a CPU spike.
                launch { uniqueCards.firstOrNull()?.let { render(it) } }
                launch { uniqueCards.drop(1).forEach { render(it) } }
            }
            DisposableEffect(rendered) {
                onDispose {
                    rendered.values.mapNotNull { it.bitmap }.distinct().forEach { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    rendered.clear()
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BarcodePanel(
                    card = card,
                    onClick = { index = (index + 1) % cards.size },
                    onLongClick = { onLongCurrent(card) },
                    preRendered = rendered[card.id],
                    usePreRendered = true,
                )
                Spacer(Modifier.height(18.dp))
                Text("${card.name} · ${i + 1}/${cards.size}", color = Grey, fontSize = 14.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
private fun CardScreen(card: Card, onLong: () -> Unit, onBack: () -> Unit) {
    KeepScreenBright()
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { SimpleTopBar(card.name, onBack) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BarcodePanel(card = card, onClick = {}, onLongClick = onLong)
        }
    }
}

@Composable
private fun rememberBarcodeRender(card: Card, targetWidthPx: Int): BarcodeRender? {
    var render by remember(card.id, card.data, card.format, targetWidthPx) { mutableStateOf<BarcodeRender?>(null) }
    LaunchedEffect(card.id, card.data, card.format, targetWidthPx) {
        val bitmap = withContext(Dispatchers.Default) {
            Barcodes.generate(card.format, card.data, targetWidthPx)
        }
        render = BarcodeRender(bitmap)
    }
    DisposableEffect(render?.bitmap) {
        val bitmap = render?.bitmap
        onDispose {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
    return render
}

@Composable
private fun KeepScreenBright() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.apply {
            attributes = attributes.apply { screenBrightness = 1f }
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.apply {
                attributes = attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
private fun ProtectSensitiveContent(enabled: Boolean) {
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
