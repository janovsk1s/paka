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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 8.dp)
            .background(White)
            .then(tapLongModifier(onClick, onLongPress, card.name))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                Text("PDF passes require Android 11 or newer", color = Grey, fontSize = 16.sp)
            render != null -> Image(
                bitmap = render.asImageBitmap(),
                contentDescription = card.name,
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(
                    with(density) { render.width.toDp() },
                    with(density) { render.height.toDp() },
                ),
            )
            renderPending -> Text("Rendering…", color = Grey, fontSize = 16.sp)
            else -> Text("Couldn't render this PDF", color = Grey, fontSize = 16.sp)
        }
    }
}

@Composable
internal fun PdfDocumentViewer(
    content: PassContent.Pdf,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("PDF passes require Android 11 or newer", color = Grey, fontSize = 16.sp)
        }
        return
    }
    PdfDocumentViewerApi30(content, onLongPress, modifier)
}

@RequiresApi(30)
@Composable
private fun PdfDocumentViewerApi30(
    content: PassContent.Pdf,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var session by remember(content.documentId) { mutableStateOf<PdfDocumentSession?>(null) }
    var error by remember(content.documentId) { mutableStateOf<String?>(null) }

    LaunchedEffect(content.documentId) {
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
                error = "PDF could not be opened"
            }
        } finally {
            opened?.close()
        }
    }
    val sessionToClose = session
    DisposableEffect(sessionToClose) {
        onDispose { sessionToClose?.close() }
    }

    val document = session
    var pageZoomed by remember(content.documentId) { mutableStateOf(false) }
    when {
        document != null -> HardCutPager(
            pageCount = minOf(content.pageCount, document.pageCount),
            modifier = modifier,
            showIndicator = content.pageCount > 1,
            gesturesEnabled = !pageZoomed,
        ) { page, _ ->
            PdfZoomPage(document, page, onLongPress) { pageZoomed = it }
        }
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(checkNotNull(error), color = Grey, fontSize = 16.sp)
        }
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Opening PDF…", color = Grey, fontSize = 16.sp)
        }
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
        val rendered = withContext(Dispatchers.Default) {
            runCatching {
                // Fit the complete page into the full viewer without changing
                // its aspect ratio. Movement is reserved for the zoomed state.
                session.renderPage(pageIndex, viewportWidth, viewportHeight)
            }
        }.getOrNull()
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
        val bitmap = withContext(Dispatchers.Default) {
            runCatching {
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
        }.getOrNull() ?: return@LaunchedEffect
        sharpLayer = SharpPdfLayer(bitmap, zoom, translationX, translationY)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
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
                        if (event.changes.none { it.pressed }) break
                        if (!handling && event.changes.any { it.isConsumed }) break
                        val pointerCount = event.changes.count { it.pressed }
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
                if (page == null) return@pointerInput
                detectTapGestures(
                    onLongPress = {
                        performPakaHaptic(context, haptics)
                        onLongPress()
                    },
                    onDoubleTap = { point ->
                        if (zoom > 1.05f) {
                            zoom = 1f
                            onZoomChanged(false)
                            translationX = 0f
                            translationY = 0f
                        } else {
                            val target = 3f
                            val anchoredX = translationX + (point.x - pageLeft - translationX) * (1f - target / zoom)
                            val anchoredY = translationY + (point.y - pageTop - translationY) * (1f - target / zoom)
                            zoom = target
                            onZoomChanged(true)
                            translationX = clampX(anchoredX, target)
                            translationY = clampY(anchoredY, target)
                        }
                    },
                )
            },
        contentAlignment = Alignment.TopStart,
    ) {
        if (page != null) {
            Image(
                bitmap = page.bitmap.asImageBitmap(),
                contentDescription = "PDF page ${pageIndex + 1}",
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
            if (session.pageCount > 1) {
                Text(
                    "${pageIndex + 1}/${session.pageCount}",
                    color = White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 8.dp)
                        .background(Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else if (renderFailed) {
            Text("Couldn't render this PDF", color = Grey, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            Text("Rendering…", color = Grey, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
