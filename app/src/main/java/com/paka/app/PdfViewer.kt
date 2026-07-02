package com.paka.app

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

@Composable
internal fun PdfDocumentPreview(
    card: Card,
    content: PassContent.Pdf,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("PDF requires Android 11+", color = Grey, fontSize = 16.sp)
        }
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    var bitmap by remember(content.documentId) { mutableStateOf<Bitmap?>(null) }
    var previewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(content.documentId, previewSize) {
        if (previewSize.width <= 0 || previewSize.height <= 0) return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            PdfStore.open(context, content.documentId).use { session ->
                session.renderPage(0, previewSize.width, previewSize.height)
            }
        }
        bitmap = rendered.bitmap
    }
    val previewBitmap = bitmap
    DisposableEffect(previewBitmap) {
        onDispose { previewBitmap?.recycle() }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 8.dp)
            .background(White)
            .then(tapLongModifier(onClick, onLongPress, card.name))
            .padding(8.dp)
            .onSizeChanged { previewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            Text("Rendering…", color = Grey, fontSize = 16.sp)
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = card.name,
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(
                    with(density) { image.width.toDp() },
                    with(density) { image.height.toDp() },
                ),
            )
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
            val result = withContext(Dispatchers.IO) { runCatching { PdfStore.open(context, content.documentId) } }
            result.onSuccess {
                opened = it
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
    var sharpLayer by remember(pageIndex) { mutableStateOf<SharpPdfLayer?>(null) }
    var zoom by remember(pageIndex) { mutableFloatStateOf(1f) }
    var translationX by remember(pageIndex) { mutableFloatStateOf(0f) }
    var translationY by remember(pageIndex) { mutableFloatStateOf(0f) }

    LaunchedEffect(session, pageIndex, viewportWidth) {
        if (viewportWidth <= 0) return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            session.renderPage(
                pageIndex,
                (viewportWidth - with(density) { 32.dp.roundToPx() }).coerceAtLeast(240),
                (viewportHeight - with(density) { 32.dp.roundToPx() }).coerceAtLeast(240),
            )
        }
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
    val pageTop = if (page == null) 0f else (viewportHeight - page.bitmap.height) / 2f

    fun clampX(value: Float, targetZoom: Float): Float {
        val width = (page?.bitmap?.width ?: 0) * targetZoom
        return if (width <= viewportWidth) (viewportWidth - width) / 2f - pageLeft
        else value.coerceIn(viewportWidth - pageLeft - width, -pageLeft)
    }
    fun clampY(value: Float, targetZoom: Float): Float {
        val height = (page?.bitmap?.height ?: 0) * targetZoom
        return if (height <= viewportHeight) (viewportHeight - height) / 2f - pageTop
        else value.coerceIn(viewportHeight - pageTop - height, -pageTop)
    }

    LaunchedEffect(session, pageIndex, page, viewportWidth, viewportHeight, zoom, translationX, translationY) {
        if (page == null || viewportWidth <= 0 || viewportHeight <= 0 || zoom <= 1.05f) {
            sharpLayer = null
            return@LaunchedEffect
        }
        delay(140)
        val bitmap = withContext(Dispatchers.Default) {
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
        sharpLayer = SharpPdfLayer(bitmap, zoom, translationX, translationY)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                viewportWidth = it.width
                viewportHeight = it.height
            }
            .pointerInput(page, viewportWidth, viewportHeight) {
                if (page == null) return@pointerInput
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val oldZoom = zoom
                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                    val anchoredX = translationX + pan.x + (centroid.x - pageLeft - translationX) * (1f - newZoom / oldZoom)
                    val anchoredY = translationY + pan.y + (centroid.y - pageTop - translationY) * (1f - newZoom / oldZoom)
                    zoom = newZoom
                    onZoomChanged(newZoom > 1.05f)
                    translationX = clampX(anchoredX, newZoom)
                    translationY = clampY(anchoredY, newZoom)
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
                    color = Grey,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 12.dp),
                )
            }
        } else {
            Text("Rendering…", color = Grey, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
