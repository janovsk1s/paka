package com.paka.app

import android.os.SystemClock
import android.util.Size
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class ScanResult(val data: String, val format: PakaFormat)

@Composable
fun ScanScreen(automaticLightEnabled: Boolean, onScanned: (ScanResult) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = context as? LifecycleOwner
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var autoLight by remember { mutableStateOf(false) }
    var lowLight by remember { mutableStateOf(false) }
    val readers = remember {
        listOf(
            BarcodeReader(
                BarcodeReader.Options(
                    formats = emptySet(),
                    tryHarder = true,
                    tryRotate = true,
                    tryInvert = true,
                    tryDownscale = false,
                    binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE,
                    maxNumberOfSymbols = 1,
                ),
            ),
            BarcodeReader(
                BarcodeReader.Options(
                    formats = emptySet(),
                    tryHarder = true,
                    tryRotate = true,
                    tryInvert = true,
                    tryDownscale = false,
                    binarizer = BarcodeReader.Binarizer.GLOBAL_HISTOGRAM,
                    maxNumberOfSymbols = 1,
                ),
            ),
        )
    }
    val handled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val executor = remember {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "paka-barcode-scan") }
    }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val cameraRef = remember { AtomicReference<Camera?>(null) }
    val previewRef = remember { AtomicReference<PreviewView?>(null) }
    val hasFlashRef = remember { AtomicBoolean(false) }
    val torchRef = remember { AtomicBoolean(false) }
    val manualLightOverride = remember { AtomicBoolean(false) }
    val lastFocusRetry = remember { AtomicLong(SystemClock.elapsedRealtime()) }

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
                                                        if (activeCamera != null && previewView.width > 0 && previewView.height > 0) {
                                                            focusAt(
                                                                activeCamera,
                                                                previewView,
                                                                previewView.width / 2f,
                                                                previewView.height / 2f,
                                                            )
                                                        }
                                                    }
                                                }

                                                val result = readers.firstNotNullOfOrNull { reader ->
                                                    reader.read(image).firstOrNull { it.text != null || it.bytes != null }
                                                }
                                                val format = result?.let { mapFormat(it.format) }
                                                if (result != null && format != null && handled.compareAndSet(false, true)) {
                                                    val bytes = result.bytes
                                                    val data = when {
                                                        result.format == BarcodeReader.Format.AZTEC && bytes != null ->
                                                            String(bytes, Charsets.ISO_8859_1)
                                                        result.text != null -> result.text!!
                                                        bytes != null -> String(bytes, Charsets.ISO_8859_1)
                                                        else -> ""
                                                    }
                                                    previewView.post {
                                                        performPakaHaptic(context, haptics)
                                                        onScanned(ScanResult(data, format))
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
                            errorMessage = "Camera could not be started"
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
                text = errorMessage ?: "Camera is unavailable",
                color = White,
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
            )
        }

        BackArrow(
            modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp),
            onBack = onBack,
        )

        if (hasFlash) {
            Text(
                text = when {
                    autoLight -> "light auto"
                    torchEnabled -> "light on"
                    else -> "light"
                },
                color = if (torchEnabled) White else Grey,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
                    .then(
                        tapModifier({
                            manualLightOverride.set(true)
                            val enabled = !torchRef.get()
                            cameraRef.get()?.cameraControl?.enableTorch(enabled)
                            torchRef.set(enabled)
                            torchEnabled = enabled
                            autoLight = false
                        }, "Toggle camera light"),
                    ),
            )
        }

        if (errorMessage == null && lifecycleOwner != null) {
            Text(
                text = if (lowLight && !torchEnabled) "low light · use light" else "tap to focus",
                color = White.copy(alpha = 0.72f),
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 24.dp),
            )
        }
    }
}

private const val LOW_LIGHT_LUMA = 48
private const val LOW_LIGHT_FRAMES = 6
private const val BRIGHT_LIGHT_FRAMES = 4
private const val FOCUS_RETRY_MS = 2_200L

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
        val frame = size.width * 0.70f
        val left = (size.width - frame) / 2f
        val top = (size.height - frame) / 2f
        val right = left + frame
        val bottom = top + frame
        val arm = frame * 0.14f
        val stroke = 2.dp.toPx()
        val color = White.copy(alpha = 0.75f)

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

private fun focusAt(camera: Camera, preview: PreviewView, x: Float, y: Float) {
    val point = preview.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
    )
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()
    camera.cameraControl.startFocusAndMetering(action)
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
    BarcodeReader.Format.CODE_39 -> PakaFormat.CODE39
    BarcodeReader.Format.CODE_93 -> PakaFormat.CODE93
    BarcodeReader.Format.CODABAR -> PakaFormat.CODABAR
    BarcodeReader.Format.ITF -> PakaFormat.ITF
    BarcodeReader.Format.DATA_BAR_EXPANDED -> PakaFormat.DATABAR_EXPANDED
    else -> null
}
