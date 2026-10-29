package com.paka.app

import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import zxingcpp.BarcodeReader
import kotlin.math.abs
import kotlin.math.hypot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class ScanResult(val data: String, val format: PakaFormat)

@Composable
fun ScanScreen(
    automaticLightEnabled: Boolean,
    @StringRes refusalMessage: Int,
    onScanned: (ScanResult) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = context as? LifecycleOwner
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var autoLight by remember { mutableStateOf(false) }
    var lowLight by remember { mutableStateOf(false) }
    val readers = remember { scanReaderOptions.map(::BarcodeReader) }
    val handled = remember { AtomicBoolean(false) }
    val refusalShown = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val executor = remember {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "paka-barcode-scan") }
    }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val viewSizeRef = remember { AtomicReference(intArrayOf(0, 0)) }
    val cameraRef = remember { AtomicReference<Camera?>(null) }
    val previewRef = remember { AtomicReference<PreviewView?>(null) }
    val hasFlashRef = remember { AtomicBoolean(false) }
    val torchRef = remember { AtomicBoolean(false) }
    val manualLightOverride = remember { AtomicBoolean(false) }
    val lastFocusRetry = remember { AtomicLong(SystemClock.elapsedRealtime()) }
    val foreground by rememberIsForeground()

    // CameraX drops the torch when it unbinds on background; clear the mirrored
    // state so the label and auto-light logic restart honestly on return.
    LaunchedEffect(foreground) {
        if (!foreground) {
            torchRef.set(false)
            manualLightOverride.set(false)
            torchEnabled = false
            autoLight = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            ContextCompat.getMainExecutor(context).execute {
                cameraRef.getAndSet(null)?.cameraControl?.enableTorch(false)
                previewRef.set(null)
                providerRef.getAndSet(null)?.unbindAll()
            }
            executor.shutdownNow()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Black)) {
        if (lifecycleOwner != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    previewRef.set(previewView)
                    previewView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                        viewSizeRef.set(intArrayOf(view.width, view.height))
                    }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            providerRef.set(provider)
                            if (disposed.get()) {
                                provider.unbindAll()
                                return@addListener
                            }
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            ),
                                        )
                                        .build(),
                                )
                                .build()
                                .also { imageAnalysis ->
                                    var darkFrames = 0
                                    var brightFrames = 0
                                    imageAnalysis.setAnalyzer(executor) { image ->
                                        try {
                                            if (!handled.get()) {
                                                val isDark = averageLuma(image) < LOW_LIGHT_LUMA
                                                if (isDark) {
                                                    darkFrames++
                                                    brightFrames = 0
                                                } else {
                                                    brightFrames++
                                                    darkFrames = 0
                                                }
                                                if (darkFrames == LOW_LIGHT_FRAMES) {
                                                    previewView.post {
                                                        lowLight = true
                                                        if (
                                                            hasFlashRef.get() &&
                                                            !torchRef.get() &&
                                                            automaticLightEnabled &&
                                                            !manualLightOverride.get()
                                                        ) {
                                                            cameraRef.get()?.cameraControl?.enableTorch(true)
                                                            torchRef.set(true)
                                                            torchEnabled = true
                                                            autoLight = true
                                                        }
                                                    }
                                                } else if (brightFrames == BRIGHT_LIGHT_FRAMES) {
                                                    previewView.post { lowLight = false }
                                                }

                                                val now = SystemClock.elapsedRealtime()
                                                val previousFocus = lastFocusRetry.get()
                                                if (
                                                    now - previousFocus >= FOCUS_RETRY_MS &&
                                                    lastFocusRetry.compareAndSet(previousFocus, now)
                                                ) {
                                                    previewView.post {
                                                        val activeCamera = cameraRef.get()
                                                        if (
                                                            activeCamera != null &&
                                                            previewView.width > 0 &&
                                                            previewView.height > 0
                                                        ) {
                                                            focusAt(
                                                                activeCamera,
                                                                previewView,
                                                                previewView.width / 2f,
                                                                previewView.height / 2f,
                                                            )
                                                        }
                                                    }
                                                }

                                                val symbols = readScanSymbols(readers) { read(image) }
                                                // A refused symbol does not end the scan: the wanted
                                                // code may sit beside it.
                                                val accepted = acceptedScans(symbols)
                                                val onlyRefused = symbols.isNotEmpty() && accepted.isEmpty()
                                                if (onlyRefused && refusalShown.compareAndSet(false, true)) {
                                                    previewView.post {
                                                        Toast.makeText(context, refusalMessage, Toast.LENGTH_LONG)
                                                            .show()
                                                    }
                                                }
                                                // zxing-cpp reports positions in the rotated
                                                // (display-oriented) frame.
                                                val rotated = image.imageInfo.rotationDegrees % 180 != 0
                                                val viewSize = viewSizeRef.get()
                                                val result = selectAimedSymbol(
                                                    centers = accepted.map { it.second },
                                                    geometry = ScanGeometry(
                                                        frameWidth = if (rotated) image.height else image.width,
                                                        frameHeight = if (rotated) image.width else image.height,
                                                        viewWidth = viewSize[0],
                                                        viewHeight = viewSize[1],
                                                    ),
                                                )?.let { accepted[it].first }
                                                if (result != null && handled.compareAndSet(false, true)) {
                                                    previewView.post {
                                                        performPakaHaptic(context, haptics)
                                                        onScanned(result)
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {
                                            // A single malformed frame should not stop scanning.
                                        } finally {
                                            image.close()
                                        }
                                    }
                                }
                            provider.unbindAll()
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                            cameraRef.set(camera)
                            val flashAvailable = camera.cameraInfo.hasFlashUnit()
                            hasFlash = flashAvailable
                            hasFlashRef.set(flashAvailable)
                            lastFocusRetry.set(SystemClock.elapsedRealtime())
                            previewView.post {
                                focusAt(camera, previewView, previewView.width / 2f, previewView.height / 2f)
                            }
                        } catch (_: Exception) {
                            errorMessage = resources.getString(R.string.scanner_error_camera_start)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )

            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { point ->
                        val camera = cameraRef.get()
                        val preview = previewRef.get()
                        if (camera != null && preview != null) focusAt(camera, preview, point.x, point.y)
                    }
                },
            )

            if (errorMessage == null) ScanGuide()
        }

        if (lifecycleOwner == null || errorMessage != null) {
            Text(
                text = errorMessage ?: stringResource(R.string.scanner_error_camera_unavailable),
                color = White,
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
            )
        }

        BackArrow(
            // Same spot as SimpleTopBar's arrow: 12 dp below the status bar, 2 dp past the edge.
            modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(top = 12.dp).offset(x = (-2).dp),
            // The viewfinder backdrop is physically dark in both palette modes.
            color = White,
            onBack = onBack,
        )

        if (hasFlash) {
            Text(
                text = when {
                    autoLight -> stringResource(R.string.scanner_label_light_auto)
                    torchEnabled -> stringResource(R.string.scanner_label_light_on)
                    else -> stringResource(R.string.scanner_label_light)
                },
                color = if (torchEnabled) White else Grey,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    // The back arrow's 48 dp lane: 12 dp below the status bar.
                    .padding(top = 12.dp)
                    .then(
                        tapModifier({
                            manualLightOverride.set(true)
                            val enabled = !torchRef.get()
                            cameraRef.get()?.cameraControl?.enableTorch(enabled)
                            torchRef.set(enabled)
                            torchEnabled = enabled
                            autoLight = false
                        }, if (torchEnabled) {
                            stringResource(R.string.scanner_cd_turn_light_off)
                        } else {
                            stringResource(R.string.scanner_cd_turn_light_on)
                        }),
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        if (errorMessage == null && lifecycleOwner != null) {
            Text(
                text = if (lowLight && !torchEnabled) {
                    stringResource(R.string.scanner_hint_low_light)
                } else {
                    stringResource(R.string.scanner_hint_tap_focus)
                },
                color = White.copy(alpha = 0.72f),
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 24.dp),
            )
        }
    }
}

private const val LOW_LIGHT_LUMA = 48
private const val LOW_LIGHT_FRAMES = 6
private const val BRIGHT_LIGHT_FRAMES = 4
internal const val FOCUS_RETRY_MS = 2_200L
private const val MAX_SCAN_SYMBOLS = 5
private const val ITF_MODULES_PER_DIGIT = 9
private const val ITF_GUARD_MODULES = 9
private const val MIN_ITF_READ_HEIGHT_MODULES = 5
internal const val SCAN_GUIDE_FRACTION = 0.70f

/**
 * The camera's decoder settings, shared with the emulator decode corpus test.
 * Reading several symbols per frame lets selectAimedSymbol require the guide
 * square to disambiguate documents that carry more than one code.
 */
internal val scanReaderOptions: List<BarcodeReader.Options>
    get() = listOf(BarcodeReader.Binarizer.LOCAL_AVERAGE, BarcodeReader.Binarizer.GLOBAL_HISTOGRAM).map {
        BarcodeReader.Options(
            formats = emptySet(),
            tryHarder = true,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = false,
            binarizer = it,
            maxNumberOfSymbols = MAX_SCAN_SYMBOLS,
        )
    }

/**
 * Symbols Paka can map, from the first reader that finds any. ITF has no mandatory check
 * digit and its stop pattern also occurs inside the data, so scan lines leaving a tilted
 * code through its top or bottom edge can decode a shorter, valid ITF. Those lines all
 * exit within one wide space, so they span at most three modules of bar height; a whole
 * read spans far more. Modules assume 3:1 wide bars.
 */
internal fun readScanSymbols(
    readers: List<BarcodeReader>,
    read: BarcodeReader.() -> List<BarcodeReader.Result>,
): List<BarcodeReader.Result> = readers.firstNotNullOfOrNull { reader ->
    reader.read().filter {
        val p = it.position
        val length = hypot((p.topRight.x - p.topLeft.x).toFloat(), (p.topRight.y - p.topLeft.y).toFloat())
        val height = hypot((p.bottomLeft.x - p.topLeft.x).toFloat(), (p.bottomLeft.y - p.topLeft.y).toFloat())
        val modules = ITF_MODULES_PER_DIGIT * (it.text?.length ?: 0) + ITF_GUARD_MODULES
        val partialItf = it.format == BarcodeReader.Format.ITF &&
            height * modules < length * MIN_ITF_READ_HEIGHT_MODULES
        (it.text != null || it.bytes != null) && mapFormat(it.format) != null && !partialItf
    }.takeIf { it.isNotEmpty() }
}.orEmpty()

/** The symbols whose exact payload Paka can import, with their centres. */
internal fun acceptedScans(symbols: List<BarcodeReader.Result>): List<Pair<ScanResult, SymbolCenter>> =
    symbols.mapNotNull { symbol ->
        val format = mapFormat(symbol.format) ?: return@mapNotNull null
        selectScanPayload(format, symbol.contentType, symbol.text, symbol.bytes)
            ?.let { ScanResult(it, format) to symbol.position.center() }
    }

/** Symbol centre in the display-oriented analysis frame zxing-cpp reports. */
internal data class SymbolCenter(val x: Float, val y: Float)

internal fun BarcodeReader.Position.center(): SymbolCenter = SymbolCenter(
    x = (topLeft.x + topRight.x + bottomRight.x + bottomLeft.x) / 4f,
    y = (topLeft.y + topRight.y + bottomRight.y + bottomLeft.y) / 4f,
)

/** Display-oriented analysis-frame and preview-view sizes for guide placement. */
internal data class ScanGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
) {
    val usable: Boolean get() = minOf(frameWidth, frameHeight, viewWidth, viewHeight) > 0
}

/**
 * Chooses the symbol the user is aiming at. A lone symbol is accepted from the
 * whole frame, keeping single-code scans as forgiving as before. When several
 * codes are visible at once, exactly one symbol centre must sit inside the
 * on-screen guide square; any other arrangement selects nothing so scanning
 * continues until the user has aimed at one code.
 */
internal fun selectAimedSymbol(
    centers: List<SymbolCenter>,
    geometry: ScanGeometry,
    guideFraction: Float = SCAN_GUIDE_FRACTION,
): Int? {
    if (centers.size <= 1) return centers.indices.firstOrNull()
    if (!geometry.usable) return null
    // FILL_CENTER scales the frame uniformly to cover the view and crops the
    // overflow evenly, so the view centre stays the frame centre and the guide
    // square (guideFraction of the view width) spans viewWidth / scale frame
    // pixels.
    val scale = maxOf(
        geometry.viewWidth.toFloat() / geometry.frameWidth,
        geometry.viewHeight.toFloat() / geometry.frameHeight,
    )
    val halfSide = guideFraction * geometry.viewWidth / scale / 2f
    val inside = centers.withIndex().filter { (_, center) ->
        abs(center.x - geometry.frameWidth / 2f) <= halfSide &&
            abs(center.y - geometry.frameHeight / 2f) <= halfSide
    }
    return inside.singleOrNull()?.index
}

/** Samples the central image area without allocating a full luminance copy. */
private fun averageLuma(image: ImageProxy): Int {
    val plane = image.planes.firstOrNull() ?: return 255
    val buffer = plane.buffer
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return 255
    val startX = width / 6
    val endX = width - startX
    val startY = height / 6
    val endY = height - startY
    val stepX = (width / 48).coerceAtLeast(1)
    val stepY = (height / 36).coerceAtLeast(1)
    var total = 0L
    var samples = 0
    var y = startY
    while (y < endY) {
        var x = startX
        while (x < endX) {
            val index = y * plane.rowStride + x * plane.pixelStride
            if (index in 0 until buffer.limit()) {
                total += buffer.get(index).toInt() and 0xFF
                samples++
            }
            x += stepX
        }
        y += stepY
    }
    return if (samples == 0) 255 else (total / samples).toInt()
}

@Composable
private fun ScanGuide() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val frame = size.width * SCAN_GUIDE_FRACTION
        val left = (size.width - frame) / 2f
        val top = (size.height - frame) / 2f
        val right = left + frame
        val bottom = top + frame
        val arm = frame * 0.14f
        val stroke = 2.dp.toPx()
        val color = White.copy(alpha = 0.75f)
        val scrim = Black.copy(alpha = 0.52f)

        drawRect(scrim, Offset.Zero, ComposeSize(size.width, top))
        drawRect(scrim, Offset(0f, bottom), ComposeSize(size.width, size.height - bottom))
        drawRect(scrim, Offset(0f, top), ComposeSize(left, frame))
        drawRect(scrim, Offset(right, top), ComposeSize(size.width - right, frame))

        drawLine(color, Offset(left, top), Offset(left + arm, top), stroke, StrokeCap.Square)
        drawLine(color, Offset(left, top), Offset(left, top + arm), stroke, StrokeCap.Square)
        drawLine(color, Offset(right, top), Offset(right - arm, top), stroke, StrokeCap.Square)
        drawLine(color, Offset(right, top), Offset(right, top + arm), stroke, StrokeCap.Square)
        drawLine(color, Offset(left, bottom), Offset(left + arm, bottom), stroke, StrokeCap.Square)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - arm), stroke, StrokeCap.Square)
        drawLine(color, Offset(right, bottom), Offset(right - arm, bottom), stroke, StrokeCap.Square)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - arm), stroke, StrokeCap.Square)
    }
}

internal fun focusAt(camera: Camera, preview: PreviewView, x: Float, y: Float) {
    val point = preview.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
    )
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()
    camera.cameraControl.startFocusAndMetering(action)
}

/**
 * The exact payload Paka can re-render, or null. zxing-cpp's default HRI text
 * adds "(01)" brackets to GS1 data and escapes control bytes as "<GS>", so only
 * plain text is taken as text; Aztec/PDF417 keep their raw bytes via Latin-1.
 */
internal fun selectScanPayload(
    format: PakaFormat,
    contentType: BarcodeReader.ContentType,
    text: String?,
    bytes: ByteArray?,
): String? = when {
    // DataBar Expanded is GS1 by definition; the verifier compares canonical AIs.
    format == PakaFormat.DATABAR_EXPANDED -> text
    // FNC1/GS1 mode is not part of the stored payload, so it cannot be reproduced.
    contentType == BarcodeReader.ContentType.GS1 -> null
    (format == PakaFormat.AZTEC || format == PakaFormat.PDF417) && bytes != null ->
        String(bytes, Charsets.ISO_8859_1)
    contentType == BarcodeReader.ContentType.TEXT -> text
    else -> null
}

private fun mapFormat(f: BarcodeReader.Format): PakaFormat? = when (f) {
    BarcodeReader.Format.QR_CODE -> PakaFormat.QR
    BarcodeReader.Format.AZTEC -> PakaFormat.AZTEC
    BarcodeReader.Format.PDF_417 -> PakaFormat.PDF417
    BarcodeReader.Format.DATA_MATRIX -> PakaFormat.DATA_MATRIX
    BarcodeReader.Format.EAN_13 -> PakaFormat.EAN13
    BarcodeReader.Format.EAN_8 -> PakaFormat.EAN8
    BarcodeReader.Format.UPC_A -> PakaFormat.UPCA
    BarcodeReader.Format.UPC_E -> PakaFormat.UPCE
    BarcodeReader.Format.CODE_128 -> PakaFormat.CODE128
    BarcodeReader.Format.CODE_39, BarcodeReader.Format.CODE_39_STD -> PakaFormat.CODE39
    BarcodeReader.Format.CODE_93 -> PakaFormat.CODE93
    BarcodeReader.Format.CODABAR -> PakaFormat.CODABAR
    BarcodeReader.Format.ITF -> PakaFormat.ITF
    BarcodeReader.Format.DATA_BAR_EXP, BarcodeReader.Format.DATA_BAR_EXP_STK -> PakaFormat.DATABAR_EXPANDED
    // CODE_39_EXT, CODE_32 and PZN report converted text, so Paka could not re-render the printed symbol.
    else -> null
}
