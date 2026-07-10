package com.paka.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

@Composable
internal fun PhotoDocumentPreview(
    card: Card,
    page: PhotoPage,
    render: Bitmap?,
    renderPending: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    accessibilityLabel: String = card.name,
    clickLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val openDetailsLabel = stringResource(R.string.accessibility_open_details)
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Hug the photo's aspect ratio (capped at the PDF preview height) so
        // the pass sits centred with its stack label right underneath, the
        // same way barcode panels do.
        val aspect = if (page.height > 0) page.width.toFloat() / page.height else 1f
        val height = minOf(420.dp, maxWidth / aspect)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .then(
                    tapLongModifier(
                        onClick = onClick,
                        onLongClick = onLongPress,
                        label = accessibilityLabel,
                        longClickLabel = openDetailsLabel,
                        clickLabel = clickLabel,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                render != null -> Image(
                    bitmap = render.asImageBitmap(),
                    contentDescription = null,
                    filterQuality = FilterQuality.Low,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                renderPending -> Text(stringResource(R.string.status_rendering), color = Grey, fontSize = 16.sp)
                else -> Text(stringResource(R.string.error_photo_open), color = Grey, fontSize = 16.sp)
            }
        }
    }
}

@Composable
internal fun PhotoDocumentViewer(
    content: PassContent.Photos,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    showPageNumbers: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var pageZoomed by remember(content.pages) { mutableStateOf(false) }
    val bitmaps = remember(content.pages) { mutableStateMapOf<String, Bitmap>() }
    val failures = remember(content.pages) { mutableStateMapOf<String, Boolean>() }
    val foreground by rememberIsForeground()

    DisposableEffect(bitmaps) {
        onDispose {
            val owned = bitmaps.values.distinctBy(System::identityHashCode)
            bitmaps.clear()
            owned.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    LaunchedEffect(content.pages, foreground) {
        if (!foreground) {
            val owned = bitmaps.values.distinctBy(System::identityHashCode)
            bitmaps.clear()
            failures.clear()
            owned.forEach { if (!it.isRecycled) it.recycle() }
            return@LaunchedEffect
        }
        // This viewer owns both decoded sides. Prefetching keeps side changes
        // immediate, while disposal/background releases identity photos rather
        // than leaving them in a process-global cache.
        val metrics = resources.displayMetrics
        content.pages.distinctBy(PhotoPage::documentId).forEach { page ->
            if (!isActive) return@LaunchedEffect
            try {
                val decoded = loadOwnedBitmap {
                    PhotoStore.decode(
                        context = context,
                        documentId = page.documentId,
                        targetWidth = (metrics.widthPixels * 2).coerceAtMost(2_400),
                        targetHeight = (metrics.heightPixels * 2).coerceAtMost(2_400),
                    )
                }
                failures.remove(page.documentId)
                bitmaps.put(page.documentId, decoded)?.let { previous ->
                    if (previous !== decoded && !previous.isRecycled) previous.recycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures[page.documentId] = true
            }
        }
    }

    HardCutPager(
        pageCount = content.pages.size,
        modifier = modifier,
        showIndicator = false,
        gesturesEnabled = !pageZoomed,
        contentKind = PagerContentKind.SIDE,
    ) { page, advance ->
        val photoPage = content.pages[page]
        PhotoZoomPage(
            page = photoPage,
            pageIndex = page,
            pageCount = content.pages.size,
            bitmap = bitmaps[photoPage.documentId],
            failed = failures[photoPage.documentId] == true,
            label = if (showPageNumbers && content.pages.size > 1) {
                stringResource(R.string.page_fraction, page + 1, content.pages.size)
            } else {
                null
            },
            onCycle = if (content.pages.size > 1) advance else null,
            onLongPress = onLongPress,
            onZoomChanged = { pageZoomed = it },
        )
    }
}

/** Native image decoding is not cancellable; retain ownership until transfer. */
internal suspend fun loadOwnedBitmap(block: suspend () -> Bitmap): Bitmap {
    val owned = AtomicReference<Bitmap?>(null)
    return try {
        withContext(NonCancellable + Dispatchers.IO) { owned.set(block()) }
        currentCoroutineContext().ensureActive()
        owned.getAndSet(null) ?: error("Photo decode returned no bitmap")
    } finally {
        owned.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
    }
}

@Composable
private fun PhotoZoomPage(
    page: PhotoPage,
    pageIndex: Int,
    pageCount: Int,
    bitmap: Bitmap?,
    failed: Boolean,
    label: String?,
    onCycle: (() -> Unit)?,
    onLongPress: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val openDetailsLabel = stringResource(R.string.accessibility_open_details)
    val sideDescription = stringResource(R.string.accessibility_side_position, pageIndex + 1, pageCount)
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var zoom by remember(page.documentId) { mutableFloatStateOf(1f) }
    var translationX by remember(page.documentId) { mutableFloatStateOf(0f) }
    var translationY by remember(page.documentId) { mutableFloatStateOf(0f) }
    val hasBitmap = bitmap != null

    val aspect = if (page.height > 0) page.width.toFloat() / page.height else 1f
    val labelBand = if (label == null) 0f else with(density) { 36.dp.toPx() }
    val availablePhotoHeight = (viewportHeight - labelBand).coerceAtLeast(0f)
    val fittedWidth = if (viewportWidth <= 0 || availablePhotoHeight <= 0f) {
        0f
    } else {
        min(viewportWidth.toFloat(), availablePhotoHeight * aspect)
    }
    val fittedHeight = if (aspect > 0f) fittedWidth / aspect else 0f
    val pageLeft = (viewportWidth - fittedWidth) / 2f
    // Reserve a fixed caption band before fitting so a full-height portrait
    // can never place its optional page number below the viewport.
    val pageTop = ((availablePhotoHeight - fittedHeight) / 2f).coerceAtLeast(0f)

    // While an axis still fits inside the viewport the photo stays centred on
    // it (instead of pinning translation to zero, which walls off the pinch
    // anchor until the photo outgrows the screen); once it spills over, the
    // finger-anchored translation applies within the viewport bounds.
    fun clampX(value: Float, targetZoom: Float): Float {
        val width = fittedWidth * targetZoom
        return if (width <= viewportWidth) (viewportWidth - width) / 2f - pageLeft
        else value.coerceIn(viewportWidth - pageLeft - width, -pageLeft)
    }
    fun clampY(value: Float, targetZoom: Float): Float {
        val height = fittedHeight * targetZoom
        if (height >= viewportHeight) return value.coerceIn(viewportHeight - pageTop - height, -pageTop)
        // While the photo still fits, ease it from its rest position towards
        // true centre as it grows, so the label offset never jumps mid-pinch.
        val range = viewportHeight - fittedHeight
        val grown = if (range > 0f) (height - fittedHeight) / range else 1f
        val restShift = (viewportHeight - fittedHeight) / 2f - pageTop
        val centerY = viewportHeight / 2f - restShift * (1f - grown)
        return centerY - height / 2f - pageTop
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .semantics(mergeDescendants = true) {
                contentDescription = sideDescription
                role = Role.Button
                onClick(label = openDetailsLabel) {
                    performPakaHaptic(context, haptics)
                    onLongPress()
                    true
                }
            }
            .onSizeChanged { viewportWidth = it.width; viewportHeight = it.height }
            .pointerInput(page.documentId, hasBitmap, viewportWidth, viewportHeight) {
                if (!hasBitmap) return@pointerInput
                awaitEachGesture {
                    var handling = false
                    var accumulatedPan = Offset.Zero
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        if (pointerCount == 0) break
                        // A second finger reclaims the gesture even after the
                        // pager consumed the first finger's drag, so a pinch
                        // never dead-locks until all fingers lift.
                        if (!handling && pointerCount <= 1 && event.changes.any { it.isConsumed }) break
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        accumulatedPan += panChange
                        if (!handling) {
                            handling = pointerCount > 1 ||
                                (zoom > 1.05f && accumulatedPan.getDistance() > viewConfiguration.touchSlop)
                        }
                        if (handling) {
                            val centroid = event.calculateCentroid()
                            val oldZoom = zoom
                            val newZoom = (oldZoom * zoomChange).coerceIn(1f, 6f)
                            val anchoredX = translationX + panChange.x +
                                (centroid.x - pageLeft - translationX) * (1f - newZoom / oldZoom)
                            val anchoredY = translationY + panChange.y +
                                (centroid.y - pageTop - translationY) * (1f - newZoom / oldZoom)
                            zoom = newZoom
                            onZoomChanged(newZoom > 1.05f)
                            translationX = clampX(anchoredX, newZoom)
                            translationY = clampY(anchoredY, newZoom)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                }
            }
            .pointerInput(page.documentId, hasBitmap, viewportWidth, viewportHeight) {
                // At fitted size a tap must fire immediately to flip sides, so
                // this deliberately has no Compose double/triple-tap detector.
                // Zoom-in is pinch-only. While zoomed, two quick taps reset.
                var lastTapMillis = 0L
                detectTapGestures(
                    onTap = if (hasBitmap) {
                        {
                            if (zoom > 1.05f) {
                                val now = SystemClock.uptimeMillis()
                                if (now - lastTapMillis <= viewConfiguration.doubleTapTimeoutMillis) {
                                    lastTapMillis = 0L
                                    zoom = 1f
                                    translationX = 0f
                                    translationY = 0f
                                    onZoomChanged(false)
                                } else {
                                    lastTapMillis = now
                                }
                            } else if (onCycle != null) {
                                performPakaHaptic(context, haptics)
                                onCycle()
                            }
                        }
                    } else {
                        null
                    },
                    onLongPress = {
                        performPakaHaptic(context, haptics)
                        onLongPress()
                    },
                )
            },
        contentAlignment = Alignment.TopStart,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                filterQuality = FilterQuality.Low,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(with(density) { fittedWidth.toDp() }, with(density) { fittedHeight.toDp() })
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom
                        scaleY = zoom
                        this.translationX = pageLeft + translationX
                        this.translationY = pageTop + translationY
                    },
            )
            if (label != null) {
                // Sits 18dp under the photo like a stack label under its code;
                // layer-driven so pinch frames never force recomposition.
                Text(
                    label,
                    color = Grey,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .clearAndSetSemantics { }
                        .graphicsLayer {
                            this.translationY = pageTop + fittedHeight + 18.dp.toPx()
                            alpha = if (zoom > 1.05f) 0f else 1f
                        },
                )
            }
        } else if (failed) {
            Text(
                stringResource(R.string.error_photo_open),
                color = Grey,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Text(
                stringResource(R.string.status_opening_photo),
                color = Grey,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
