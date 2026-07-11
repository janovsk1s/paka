package com.paka.app

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

private data class SharpPdfLayer(
    val bitmap: Bitmap,
    val zoom: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * Presentational page-1 preview. Rendering is owned by the caller (StackScreen
 * pre-renders into its shared map) so revisiting a page never re-decrypts.
 */
@Composable
internal fun PdfDocumentPreview(
    card: Card,
    render: Bitmap?,
    renderPending: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val openDetailsLabel = stringResource(R.string.accessibility_open_details)
    val nextPassLabel = stringResource(R.string.accessibility_next_pass)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 8.dp)
            .background(White)
            .then(
                tapLongModifier(
                    onClick = onClick,
                    onLongClick = onLongPress,
                    label = card.name,
                    longClickLabel = openDetailsLabel,
                    clickLabel = nextPassLabel,
                ),
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                Text(stringResource(R.string.pdf_requires_android_11), color = Grey, fontSize = 16.sp)
            render != null -> Image(
                bitmap = render.asImageBitmap(),
                contentDescription = null,
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(
                    with(density) { render.width.toDp() },
                    with(density) { render.height.toDp() },
                ),
            )
            renderPending -> Text(stringResource(R.string.status_rendering), color = Grey, fontSize = 16.sp)
            else -> Text(stringResource(R.string.error_pdf_render), color = Grey, fontSize = 16.sp)
        }
    }
}

@Composable
internal fun PdfDocumentViewer(
    content: PassContent.Pdf,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    onPageChanged: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        PdfStatusBox(
            message = stringResource(R.string.pdf_requires_android_11),
            modifier = modifier,
            onLongPress = onLongPress,
        )
        return
    }
    PdfDocumentViewerApi30(content, onLongPress, modifier, onPageChanged)
}

@RequiresApi(30)
@Composable
private fun PdfDocumentViewerApi30(
    content: PassContent.Pdf,
    onLongPress: () -> Unit,
    modifier: Modifier,
    onPageChanged: (page: Int, pageCount: Int) -> Unit,
) {
    val context = LocalContext.current
    val foreground by rememberIsForeground()
    var session by remember(content.documentId) { mutableStateOf<PdfDocumentSession?>(null) }
    var error by remember(content.documentId) { mutableStateOf<String?>(null) }
    val openError = stringResource(R.string.error_pdf_open)

    // Open only while foregrounded; release the session (a memfd + PdfRenderer)
    // and its native memory when backgrounded, matching how decoded photos are
    // freed on stop. It reopens on return.
    LaunchedEffect(content.documentId, foreground) {
        if (!foreground || session != null) return@LaunchedEffect
        var opened: PdfDocumentSession? = null
        try {
            // The holder is assigned inside the IO block so a cancellation that
            // interrupts withContext after the open cannot leak the session.
            val result = withContext(Dispatchers.IO) {
                runCatching { PdfStore.open(context, content.documentId).also { opened = it } }
            }
            result.onSuccess {
                session = it
                opened = null
            }.onFailure {
                error = openError
            }
        } finally {
            opened?.close()
        }
    }
    DisposableEffect(content.documentId, foreground) {
        onDispose {
            session?.close()
            session = null
        }
    }

    val document = session
    var pageZoomed by remember(content.documentId) { mutableStateOf(false) }
    when {
        document != null -> {
            val pageCount = minOf(content.pageCount, document.pageCount)
            HardCutPager(
                pageCount = pageCount,
                modifier = modifier,
                indicatorOffset = 0.dp,
                showIndicator = content.pageCount > 1,
                // Drawn over the physically dark document backdrop.
                indicatorColor = White,
                gesturesEnabled = !pageZoomed,
                onPageChange = { onPageChanged(it, pageCount) },
            ) { page, _ ->
                PdfZoomPage(document, page, onLongPress) { pageZoomed = it }
            }
        }
        error != null -> PdfStatusBox(checkNotNull(error), modifier, onLongPress)
        else -> PdfStatusBox(stringResource(R.string.status_opening_pdf), modifier, onLongPress)
    }
}

@Composable
private fun PdfStatusBox(
    message: String,
    modifier: Modifier,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                longPressModifier(
                    onLongClick = onLongPress,
                    label = stringResource(R.string.accessibility_pdf_document),
                    longClickLabel = stringResource(R.string.accessibility_open_details),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = Grey, fontSize = 16.sp)
    }
}

@RequiresApi(30)
@Composable
private fun PdfZoomPage(
    session: PdfDocumentSession,
    pageIndex: Int,
    onLongPress: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val openDetailsLabel = stringResource(R.string.accessibility_open_details)
    val pageDescription = stringResource(R.string.accessibility_pdf_page, pageIndex + 1)
    var viewportWidth by remember { mutableStateOf(0) }
    var viewportHeight by remember { mutableStateOf(0) }
    var basePage by remember(pageIndex) { mutableStateOf<PdfPageBitmap?>(null) }
    var renderFailed by remember(pageIndex) { mutableStateOf(false) }
    var sharpLayer by remember(pageIndex) { mutableStateOf<SharpPdfLayer?>(null) }
    var zoom by remember(pageIndex) { mutableFloatStateOf(1f) }
    var translationX by remember(pageIndex) { mutableFloatStateOf(0f) }
    var translationY by remember(pageIndex) { mutableFloatStateOf(0f) }

    LaunchedEffect(session, pageIndex, viewportWidth, viewportHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return@LaunchedEffect
        val rendered = try {
            loadOwnedPdfPage {
                // Fit the complete page into the full viewer without changing
                // its aspect ratio. Movement is reserved for the zoomed state.
                session.renderPage(pageIndex, viewportWidth, viewportHeight)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (rendered == null) {
            renderFailed = true
            return@LaunchedEffect
        }
        renderFailed = false
        basePage = rendered
        zoom = 1f
        translationX = 0f
        translationY = 0f
        onZoomChanged(false)
    }

    val baseBitmap = basePage?.bitmap
    DisposableEffect(baseBitmap) {
        onDispose { baseBitmap?.recycle() }
    }
    val sharpBitmap = sharpLayer?.bitmap
    DisposableEffect(sharpBitmap) {
        onDispose { sharpBitmap?.recycle() }
    }

    val page = basePage
    val pageLeft = if (page == null) 0f else (viewportWidth - page.bitmap.width) / 2f
    // Tall documents share the opened-barcode top edge. Landscape documents
    // sit in the vertical middle so a shallow page does not look top-heavy.
    val pageTop = if (page != null && page.bitmap.width > page.bitmap.height) {
        (viewportHeight - page.bitmap.height) / 2f
    } else {
        0f
    }
    fun clampX(value: Float, targetZoom: Float): Float {
        val width = (page?.bitmap?.width ?: 0) * targetZoom
        return if (width <= viewportWidth) (viewportWidth - width) / 2f - pageLeft
        else value.coerceIn(viewportWidth - pageLeft - width, -pageLeft)
    }
    fun clampY(value: Float, targetZoom: Float): Float {
        val height = (page?.bitmap?.height ?: 0) * targetZoom
        return if (height <= viewportHeight) 0f
        else value.coerceIn(viewportHeight - pageTop - height, -pageTop)
    }

    LaunchedEffect(session, pageIndex, page, viewportWidth, viewportHeight, zoom, translationX, translationY) {
        if (page == null || viewportWidth <= 0 || viewportHeight <= 0 || zoom <= 1.05f) {
            sharpLayer = null
            return@LaunchedEffect
        }
        delay(140)
        val bitmap = try {
            loadOwnedPdfBitmap {
                session.renderViewport(
                    index = pageIndex,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    baseScale = page.bitmap.width.toFloat() / page.pageWidth,
                    zoom = zoom,
                    pageLeft = pageLeft,
                    pageTop = pageTop,
                    translationX = translationX,
                    translationY = translationY,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        sharpLayer = SharpPdfLayer(bitmap, zoom, translationX, translationY)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .semantics(mergeDescendants = true) {
                contentDescription = pageDescription
                role = Role.Button
                onClick(label = openDetailsLabel) {
                    performPakaHaptic(context, haptics)
                    onLongPress()
                    true
                }
            }
            .onSizeChanged {
                viewportWidth = it.width
                viewportHeight = it.height
            }
            .pointerInput(page, viewportWidth, viewportHeight) {
                if (page == null) return@pointerInput
                // At rest the page is locked and drags belong to HardCutPager.
                // Pinches and one-finger movement are consumed only while zoomed.
                awaitEachGesture {
                    var handling = false
                    var slopPan = Offset.Zero
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
                        if (!handling) {
                            slopPan += panChange
                            handling = pointerCount > 1 ||
                                (zoom > 1.05f && slopPan.getDistance() > viewConfiguration.touchSlop)
                        }
                        if (handling) {
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val centroid = event.calculateCentroid()
                                val oldZoom = zoom
                                val newZoom = (oldZoom * zoomChange).coerceIn(1f, 8f)
                                val anchoredX = translationX + panChange.x +
                                    (centroid.x - pageLeft - translationX) * (1f - newZoom / oldZoom)
                                val anchoredY = translationY + panChange.y +
                                    (centroid.y - pageTop - translationY) * (1f - newZoom / oldZoom)
                                zoom = newZoom
                                onZoomChanged(newZoom > 1.05f)
                                translationX = clampX(anchoredX, newZoom)
                                translationY = clampY(anchoredY, newZoom)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                }
            }
            .pointerInput(page, viewportWidth, viewportHeight) {
                detectTapGestures(
                    onLongPress = {
                        performPakaHaptic(context, haptics)
                        onLongPress()
                    },
                    onDoubleTap = if (page != null) {
                        { point ->
                            if (zoom > 1.05f) {
                                zoom = 1f
                                onZoomChanged(false)
                                translationX = 0f
                                translationY = 0f
                            } else {
                                val target = 3f
                                val anchoredX = translationX +
                                    (point.x - pageLeft - translationX) * (1f - target / zoom)
                                val anchoredY = translationY +
                                    (point.y - pageTop - translationY) * (1f - target / zoom)
                                zoom = target
                                onZoomChanged(true)
                                translationX = clampX(anchoredX, target)
                                translationY = clampY(anchoredY, target)
                            }
                        }
                    } else {
                        null
                    },
                )
            },
        contentAlignment = Alignment.TopStart,
    ) {
        if (page != null) {
            Image(
                bitmap = page.bitmap.asImageBitmap(),
                contentDescription = null,
                filterQuality = FilterQuality.Low,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(
                        with(density) { page.bitmap.width.toDp() },
                        with(density) { page.bitmap.height.toDp() },
                    )
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom
                        scaleY = zoom
                        this.translationX = pageLeft + translationX
                        this.translationY = pageTop + translationY
                    },
            )
            val sharp = sharpLayer
            if (sharp != null &&
                abs(sharp.zoom - zoom) < 0.001f &&
                abs(sharp.translationX - translationX) < 0.5f &&
                abs(sharp.translationY - translationY) < 0.5f
            ) {
                Image(
                    bitmap = sharp.bitmap.asImageBitmap(),
                    contentDescription = null,
                    filterQuality = FilterQuality.None,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (renderFailed) {
            Text(
                stringResource(R.string.error_pdf_render),
                color = Grey,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Text(
                stringResource(R.string.status_rendering),
                color = Grey,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Native PDF rendering is not cancellable; retain ownership until Compose accepts the page. */
private suspend fun loadOwnedPdfPage(block: suspend () -> PdfPageBitmap): PdfPageBitmap {
    return loadOwnedPdfRender(block) { page ->
        if (!page.bitmap.isRecycled) page.bitmap.recycle()
    }
}

/** Native PDF rendering is not cancellable; retain ownership until Compose accepts the bitmap. */
internal suspend fun loadOwnedPdfBitmap(block: suspend () -> Bitmap): Bitmap =
    loadOwnedPdfRender(block) { bitmap ->
        if (!bitmap.isRecycled) bitmap.recycle()
    }

private suspend fun <T : Any> loadOwnedPdfRender(
    block: suspend () -> T,
    recycle: (T) -> Unit,
): T {
    val owned = AtomicReference<T?>(null)
    return try {
        withContext(NonCancellable + Dispatchers.Default) { owned.set(block()) }
        currentCoroutineContext().ensureActive()
        owned.getAndSet(null) ?: error("PDF render returned no result")
    } finally {
        owned.getAndSet(null)?.let(recycle)
    }
}
