package com.paka.app

import android.app.Activity
import android.os.Build
import android.view.Window
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

private data class BarcodeRender(
    val bitmap: android.graphics.Bitmap?,
    val recycleOnDiscard: Boolean = false,
)

private val PassCaptionHeight = 17.dp

@Composable
private fun BarcodePanel(
    card: Card,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    preRendered: BarcodeRender? = null,
    usePreRendered: Boolean = false,
    longPressOnly: Boolean = false,
    clickLabel: String? = null,
) {
    val openDetailsLabel = stringResource(R.string.accessibility_open_details)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(White)
            .then(
                if (longPressOnly) {
                    longPressModifier(
                        onLongClick = onLongClick,
                        label = card.name,
                        longClickLabel = openDetailsLabel,
                    )
                } else {
                    tapLongModifier(
                        onClick = onClick,
                        onLongClick = onLongClick,
                        label = card.name,
                        longClickLabel = openDetailsLabel,
                        clickLabel = clickLabel,
                    )
                },
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
                        contentDescription = null,
                        modifier = Modifier.size(imageWidth, imageHeight),
                        contentScale = ContentScale.Fit,
                    )
                }
                render == null -> Text(stringResource(R.string.status_rendering), color = Grey, fontSize = 16.sp)
                else -> Text(stringResource(R.string.error_code_render), color = Grey, fontSize = 16.sp)
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
    val foreground by rememberIsForeground()
    val nextPassLabel = stringResource(R.string.accessibility_next_pass)
    val nextSideLabel = stringResource(R.string.accessibility_next_side)
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            SimpleTopBar(name, onBack, capitalizeTitle = false)
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val targetWidthPx = with(density) { (maxWidth - 32.dp).roundToPx() }
            // Matches PdfDocumentPreview's 420.dp box minus its 8.dp inner padding.
            val pdfHeightPx = with(density) { 404.dp.roundToPx() }
            var currentIndex by remember(name, cards) { mutableIntStateOf(0) }
            val rendered = remember(name, cards, targetWidthPx) { mutableStateMapOf<String, BarcodeRender>() }
            val photoRenders = remember(name, cards, targetWidthPx) { mutableStateMapOf<String, BarcodeRender>() }
            DisposableEffect(photoRenders) {
                onDispose {
                    val owned = photoRenders.values.mapNotNull(BarcodeRender::bitmap)
                        .distinctBy(System::identityHashCode)
                    photoRenders.clear()
                    owned.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
            // The rendered map mixes shared-cache barcode bitmaps (which must not
            // be recycled) with owned PDF page bitmaps; recycle only the latter.
            DisposableEffect(rendered) {
                onDispose {
                    rendered.values.filter(BarcodeRender::recycleOnDiscard)
                        .mapNotNull(BarcodeRender::bitmap)
                        .distinctBy(System::identityHashCode)
                        .forEach { if (!it.isRecycled) it.recycle() }
                    rendered.clear()
                }
            }

            LaunchedEffect(cards, targetWidthPx, foreground, currentIndex) {
                val uniqueCards = cards.distinctBy { it.id }
                if (!foreground) {
                    val photos = photoRenders.values.mapNotNull(BarcodeRender::bitmap)
                        .distinctBy(System::identityHashCode)
                    photoRenders.clear()
                    photos.forEach { if (!it.isRecycled) it.recycle() }
                    val documents = rendered.values.filter(BarcodeRender::recycleOnDiscard)
                        .mapNotNull(BarcodeRender::bitmap)
                        .distinctBy(System::identityHashCode)
                    rendered.clear()
                    documents.forEach { if (!it.isRecycled) it.recycle() }
                    return@LaunchedEffect
                }
                val index = currentIndex.coerceIn(0, uniqueCards.lastIndex)
                val desiredCards = listOf(
                    uniqueCards[index],
                    uniqueCards[(index + 1) % uniqueCards.size],
                ).distinctBy { it.id }
                val desiredIds = desiredCards.mapTo(hashSetOf(), Card::id)
                val desiredPhotoKeys = desiredCards.flatMap { card ->
                    card.photoContent?.pages.orEmpty().map { page -> "${card.id}:${page.documentId}" }
                }.toSet()
                photoRenders.keys.toList().filterNot(desiredPhotoKeys::contains).forEach { key ->
                    photoRenders.remove(key)?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                }
                rendered.keys.toList().filterNot(desiredIds::contains).forEach { id ->
                    rendered.remove(id)?.takeIf(BarcodeRender::recycleOnDiscard)?.bitmap
                        ?.let { if (!it.isRecycled) it.recycle() }
                }

                suspend fun render(stackCard: Card) {
                    val photoContent = stackCard.photoContent
                    if (photoContent != null) {
                        photoContent.pages.distinctBy(PhotoPage::documentId).forEach { page ->
                            val key = "${stackCard.id}:${page.documentId}"
                            if (key in photoRenders) return@forEach
                            val bitmap = try {
                                loadOwnedBitmap {
                                    PhotoStore.decode(context, page.documentId, targetWidthPx, pdfHeightPx)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            val previous = photoRenders.put(key, BarcodeRender(bitmap))?.bitmap
                            if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
                        }
                        return
                    }
                    if (stackCard.id in rendered) return
                    val bitmap = when (val content = stackCard.content) {
                        is PassContent.Barcode -> withContext(Dispatchers.Default) {
                            Barcodes.generateCached(content.format, content.data, targetWidthPx)
                        }
                        is PassContent.Pdf -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            null
                        } else {
                            try {
                                loadOwnedBitmap {
                                    PdfStore.open(context, content.documentId).use { session ->
                                        session.renderPage(0, targetWidthPx, pdfHeightPx).bitmap
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                        is PassContent.Photos -> error("Photo content was handled above")
                    }
                    rendered[stackCard.id] = BarcodeRender(
                        bitmap = bitmap,
                        recycleOnDiscard = stackCard.content is PassContent.Pdf && bitmap != null,
                    )
                }
                // Bound native previews to the visible card and the next card
                // in the tap cycle. Photo passes still prefetch both sides.
                desiredCards.forEach { render(it) }
            }
            HardCutPager(
                pageCount = cards.size,
                showIndicator = false,
                contentKind = PagerContentKind.PASS,
                onPageChange = { currentIndex = it },
            ) { index, advance ->
                val card = cards[index]
                val photoContent = card.photoContent
                var photoSide by remember(card.id, photoContent?.pages) { mutableIntStateOf(0) }
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
                            clickLabel = nextPassLabel,
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
                            val renderKey = "${card.id}:${content.pages[photoSide].documentId}"
                            val render = photoRenders[renderKey]
                            val previewLabel = stringResource(
                                R.string.accessibility_named_photo_side,
                                card.name,
                                photoSide + 1,
                                content.pages.size,
                            )
                            PhotoDocumentPreview(
                                card = card,
                                page = content.pages[photoSide],
                                render = render?.bitmap,
                                renderPending = renderKey !in photoRenders,
                                accessibilityLabel = previewLabel,
                                clickLabel = if (photoSide < content.pages.size - 1) nextSideLabel else nextPassLabel,
                                // Tapping steps through the sides first, then on
                                // to the next card so the stack's tap cycle
                                // continues past two-sided photo passes.
                                onClick = {
                                    if (photoSide < content.pages.size - 1) {
                                        photoSide += 1
                                    } else {
                                        photoSide = 0
                                        advance()
                                    }
                                },
                                onLongPress = { onLongCurrent(card) },
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clearAndSetSemantics { },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.pass_stack_position, card.name, index + 1, cards.size),
                            color = Grey,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Light,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                        )
                        if (photoContent != null && photoContent.pages.size > 1) {
                            Text(
                                stringResource(R.string.page_fraction, photoSide + 1, photoContent.pages.size),
                                color = Grey,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CardScreen(card: Card, forceMaximumBrightness: Boolean, onLong: () -> Unit, onBack: () -> Unit) {
    KeepScreenBright(forceMaximumBrightness)
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            SimpleTopBar(card.name, onBack, capitalizeTitle = false)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BarcodePanel(card = card, onClick = {}, onLongClick = onLong, longPressOnly = true)
                Spacer(Modifier.height(18.dp))
                // Fixed stand-in for StackScreen's one-line caption. A spacer
                // preserves alignment without exposing invisible accessibility text.
                Spacer(Modifier.height(PassCaptionHeight))
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
    val resources = LocalResources.current
    val showPageNumbers = remember { Prefs.pageNumbers(context) }
    var pageLabel by remember(content.documentId) { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            SimpleTopBar(
                card.name,
                onBack,
                trailing = if (showPageNumbers) pageLabel else null,
                capitalizeTitle = false,
            )
        }
        PdfDocumentViewer(
            content = content,
            onLongPress = onLong,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onPageChanged = { page, pageCount ->
                pageLabel = if (pageCount > 1) {
                    resources.getString(R.string.page_fraction, page + 1, pageCount)
                } else {
                    null
                }
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
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            SimpleTopBar(card.name, onBack, capitalizeTitle = false)
        }
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
    var render by remember(card.id, barcode.data, barcode.format, targetWidthPx) {
        mutableStateOf<BarcodeRender?>(null)
    }
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
    val window = (context as? Activity)?.window
    DisposableEffect(window, enabled) {
        if (enabled && window != null) SensitiveWindowProtection.acquire(window)
        onDispose {
            if (enabled && window != null) SensitiveWindowProtection.release(window)
        }
    }
}

/** Prevents one nested sensitive screen from clearing another owner's flag. */
internal object SensitiveWindowProtection {
    private val owners = WeakHashMap<Window, Int>()

    @Synchronized
    fun acquire(window: Window) {
        val count = owners[window] ?: 0
        if (count == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        owners[window] = count + 1
    }

    @Synchronized
    fun release(window: Window) {
        val count = owners[window] ?: return
        if (count <= 1) {
            owners.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            owners[window] = count - 1
        }
    }
}
