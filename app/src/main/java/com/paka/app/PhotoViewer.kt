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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
internal fun PhotoDocumentPreview(
    card: Card,
    page: PhotoPage,
    render: Bitmap?,
    renderPending: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                .then(tapLongModifier(onClick, onLongPress, card.name)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                render != null -> Image(
                    bitmap = render.asImageBitmap(),
                    contentDescription = card.name,
                    filterQuality = FilterQuality.Low,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                renderPending -> Text("Rendering…", color = Grey, fontSize = 16.sp)
                else -> Text("Couldn't open this photo", color = Grey, fontSize = 16.sp)
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
    var pageZoomed by remember(content.pages) { mutableStateOf(false) }
    val bitmaps = remember(content.pages) { mutableStateMapOf<String, Bitmap>() }
    val failures = remember(content.pages) { mutableStateMapOf<String, Boolean>() }
    val ownedBitmaps = remember(content.pages) { mutableListOf<Bitmap>() }

    DisposableEffect(content.pages) {
        onDispose {
            ownedBitmaps.distinctBy(System::identityHashCode).forEach { if (!it.isRecycled) it.recycle() }
            ownedBitmaps.clear()
            bitmaps.clear()
            failures.clear()
        }
    }

    LaunchedEffect(content.pages) {
        suspend fun decodePass(targetWidth: Int, targetHeight: Int) {
            content.pages.forEach { page ->
                if (!currentCoroutineContext().isActive) return
                val result = withContext(Dispatchers.IO) {
                    runCatching { PhotoStore.decode(context, page.documentId, targetWidth, targetHeight) }
                }
                result
                    .onSuccess { decoded ->
                        failures.remove(page.documentId)
                        ownedBitmaps += decoded
                        bitmaps[page.documentId] = decoded
                    }
                    .onFailure { failures[page.documentId] = true }
            }
        }
        // Quick pass so every side opens near-instantly, then a sharp pass at
        // full viewing resolution. Display metrics avoid waiting for layout.
        val metrics = context.resources.displayMetrics
        decodePass(metrics.widthPixels / 2, metrics.heightPixels / 2)
        decodePass((metrics.widthPixels * 2).coerceAtMost(2_400), (metrics.heightPixels * 2).coerceAtMost(2_400))
    }

    HardCutPager(
        pageCount = content.pages.size,
        modifier = modifier,
        showIndicator = false,
        gesturesEnabled = !pageZoomed,
    ) { page, advance ->
        val photoPage = content.pages[page]
        PhotoZoomPage(
            page = photoPage,
            pageIndex = page,
            bitmap = bitmaps[photoPage.documentId],
            failed = failures[photoPage.documentId] == true,
            label = if (showPageNumbers && content.pages.size > 1) "${page + 1}/${content.pages.size}" else null,
            onCycle = if (content.pages.size > 1) advance else null,
            onLongPress = onLongPress,
            onZoomChanged = { pageZoomed = it },
        )
    }
}

@Composable
private fun PhotoZoomPage(
    page: PhotoPage,
    pageIndex: Int,
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
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var zoom by remember(page.documentId) { mutableFloatStateOf(1f) }
    var translationX by remember(page.documentId) { mutableFloatStateOf(0f) }
    var translationY by remember(page.documentId) { mutableFloatStateOf(0f) }
    val hasBitmap = bitmap != null

    val aspect = if (page.height > 0) page.width.toFloat() / page.height else 1f
    val fittedWidth = if (viewportWidth <= 0 || viewportHeight <= 0) 0f else min(viewportWidth.toFloat(), viewportHeight * aspect)
    val fittedHeight = if (aspect > 0f) fittedWidth / aspect else 0f
    val pageLeft = (viewportWidth - fittedWidth) / 2f
    // The label block under the photo is part of the centred composition —
    // exactly like a stack label under a code — so the photo rides half the
    // block above true centre.
    val labelShift = if (label != null) with(density) { 19.dp.toPx() } else 0f
    val pageTop = ((viewportHeight - fittedHeight) / 2f - labelShift).coerceAtLeast(0f)

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
                if (!hasBitmap) return@pointerInput
                // At fitted size a tap must fire immediately to flip sides, so
                // this deliberately has no Compose double/triple-tap detector.
                // Zoom-in is pinch-only. While zoomed, two quick taps reset.
                var lastTapMillis = 0L
                detectTapGestures(
                    onTap = {
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
                contentDescription = "Photo side ${pageIndex + 1}",
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
                        .graphicsLayer {
                            this.translationY = pageTop + fittedHeight + 18.dp.toPx()
                            alpha = if (zoom > 1.05f) 0f else 1f
                        },
                )
            }
        } else if (failed) {
            Text("Couldn't open this photo", color = Grey, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            Text("Opening photo…", color = Grey, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
