package com.paka.app

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BarcodeRender(val bitmap: android.graphics.Bitmap?)

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
internal fun StackScreen(
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
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { SimpleTopBar(name, onBack) }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val targetWidthPx = with(density) { (maxWidth - 32.dp).roundToPx() }
            // Matches PdfDocumentPreview's 420.dp box minus its 8.dp inner padding.
            val pdfHeightPx = with(density) { 404.dp.roundToPx() }
            val rendered = remember(name, cards, targetWidthPx) { mutableStateMapOf<String, BarcodeRender>() }
            val photoRenders = remember(name, cards, targetWidthPx) { mutableStateMapOf<String, BarcodeRender>() }
            DisposableEffect(photoRenders) {
                onDispose {
                    photoRenders.values.mapNotNull(BarcodeRender::bitmap)
                        .distinctBy(System::identityHashCode)
                        .forEach { if (!it.isRecycled) it.recycle() }
                    photoRenders.clear()
                }
            }
            // The rendered map mixes shared-cache barcode bitmaps (which must not
            // be recycled) with owned PDF page bitmaps; recycle only the latter.
            DisposableEffect(rendered) {
                onDispose {
                    cards.filter { it.content is PassContent.Pdf }
                        .mapNotNull { rendered[it.id]?.bitmap }
                        .distinctBy(System::identityHashCode)
                        .forEach { if (!it.isRecycled) it.recycle() }
                    rendered.clear()
                }
            }

            LaunchedEffect(cards, targetWidthPx) {
                val uniqueCards = cards.distinctBy { it.id }
                suspend fun render(stackCard: Card) {
                    val photoContent = stackCard.photoContent
                    if (photoContent != null) {
                        photoContent.pages.forEach { page ->
                            val key = "${stackCard.id}:${page.documentId}"
                            val bitmap = withContext(Dispatchers.IO) {
                                runCatching {
                                    PhotoStore.decode(context, page.documentId, targetWidthPx, pdfHeightPx)
                                }.getOrNull()
                            }
                            photoRenders[key] = BarcodeRender(bitmap)
                        }
                        return
                    }
                    val bitmap = when (val content = stackCard.content) {
                        is PassContent.Barcode -> withContext(Dispatchers.Default) {
                            Barcodes.generateCached(content.format, content.data, targetWidthPx)
                        }
                        is PassContent.Pdf ->
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null
                            else withContext(Dispatchers.IO) {
                                runCatching {
                                    PdfStore.open(context, content.documentId).use { session ->
                                        session.renderPage(0, targetWidthPx, pdfHeightPx).bitmap
                                    }
                                }.getOrNull()
                            }
                        is PassContent.Photos -> error("Photo content was handled above")
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
                    when (val content = card.content) {
                        is PassContent.Barcode -> BarcodePanel(
                            card = card,
                            onClick = advance,
                            onLongClick = { onLongCurrent(card) },
                            preRendered = rendered[card.id],
                            usePreRendered = true,
                        )
                        is PassContent.Pdf -> {
                            val render = rendered[card.id]
                            PdfDocumentPreview(
                                card = card,
                                render = render?.bitmap,
                                renderPending = render == null,
                                onClick = advance,
                                onLongPress = { onLongCurrent(card) },
                            )
                        }
                        is PassContent.Photos -> {
                            var side by remember(card.id) { mutableIntStateOf(0) }
                            val renderKey = "${card.id}:${content.pages[side].documentId}"
                            val render = photoRenders[renderKey]
                            PhotoDocumentPreview(
                                card = card,
                                page = content.pages[side],
                                render = render?.bitmap,
                                renderPending = renderKey !in photoRenders,
                                // Tapping steps through the sides first, then on
                                // to the next card so the stack's tap cycle
                                // continues past two-sided photo passes.
                                onClick = {
                                    if (side < content.pages.size - 1) {
                                        side += 1
                                    } else {
                                        side = 0
                                        advance()
                                    }
                                },
                                onLongPress = { onLongCurrent(card) },
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("${card.name} · ${index + 1}/${cards.size}", color = Grey, fontSize = 14.sp, fontWeight = FontWeight.Light)
                }
            }
        }
    }
}

@Composable
internal fun CardScreen(card: Card, forceMaximumBrightness: Boolean, onLong: () -> Unit, onBack: () -> Unit) {
    KeepScreenBright(forceMaximumBrightness)
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { SimpleTopBar(card.name, onBack) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BarcodePanel(card = card, onClick = {}, onLongClick = onLong, longPressOnly = true)
                Spacer(Modifier.height(18.dp))
                // Invisible stand-in for StackScreen's caption so the barcode sits at
                // the same vertical position whether the pass is viewed alone or in a stack.
                Text("${card.name} · 1/1", color = Color.Transparent, fontSize = 14.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
internal fun PdfScreen(
    card: Card,
    content: PassContent.Pdf,
    forceMaximumBrightness: Boolean,
    onLong: () -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenBright(forceMaximumBrightness)
    val context = LocalContext.current
    val showPageNumbers = remember { Prefs.pageNumbers(context) }
    var pageLabel by remember(content.documentId) { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            SimpleTopBar(card.name, onBack, trailing = if (showPageNumbers) pageLabel else null)
        }
        PdfDocumentViewer(
            content = content,
            onLongPress = onLong,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onPageChanged = { page, pageCount ->
                pageLabel = if (pageCount > 1) "${page + 1}/$pageCount" else null
            },
        )
    }
}

@Composable
internal fun PhotoScreen(
    card: Card,
    content: PassContent.Photos,
    forceMaximumBrightness: Boolean,
    onLong: () -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenBright(forceMaximumBrightness)
    val context = LocalContext.current
    val showPageNumbers = remember { Prefs.pageNumbers(context) }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { SimpleTopBar(card.name, onBack) }
        PhotoDocumentViewer(
            content = content,
            onLongPress = onLong,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            showPageNumbers = showPageNumbers,
        )
    }
}

@Composable
private fun rememberBarcodeRender(card: Card, targetWidthPx: Int): BarcodeRender? {
    val barcode = card.barcodeContent ?: return BarcodeRender(null)
    var render by remember(card.id, barcode.data, barcode.format, targetWidthPx) { mutableStateOf<BarcodeRender?>(null) }
    LaunchedEffect(card.id, barcode.data, barcode.format, targetWidthPx) {
        val bitmap = withContext(Dispatchers.Default) {
            Barcodes.generateCached(barcode.format, barcode.data, targetWidthPx)
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
internal fun ProtectSensitiveContent(enabled: Boolean) {
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
