package com.paka.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Photographs a document straight into the encrypted photo store. The frame
 * travels sensor → RAM → normalization → ciphertext; no file, media store
 * entry, or gallery thumbnail ever exists.
 */
@Composable
internal fun PhotoCaptureScreen(
    onCaptured: (PhotoImport) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var normalized by remember { mutableStateOf<ByteArray?>(null) }
    var confirmBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cropping by remember { mutableStateOf(false) }
    var cropRect by remember { mutableStateOf(CropRect.FULL) }
    val disposed = remember { AtomicBoolean(false) }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val cameraRef = remember { AtomicReference<Camera?>(null) }
    val previewRef = remember { AtomicReference<PreviewView?>(null) }
    val captureRef = remember { AtomicReference<ImageCapture?>(null) }
    val torchRef = remember { AtomicBoolean(false) }

    // A live view of someone's ID is never a sharing feature.
    ProtectSensitiveContent(true)

    fun discardCapture() {
        normalized?.fill(0)
        normalized = null
        confirmBitmap?.recycle()
        confirmBitmap = null
        cropping = false
        cropRect = CropRect.FULL
    }

    fun captureFailed(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            normalized?.fill(0)
            confirmBitmap?.recycle()
            ContextCompat.getMainExecutor(context).execute {
                cameraRef.getAndSet(null)?.cameraControl?.enableTorch(false)
                previewRef.set(null)
                providerRef.getAndSet(null)?.unbindAll()
            }
        }
    }

    BackHandler {
        when {
            cropping -> {
                cropping = false
                cropRect = CropRect.FULL
            }
            normalized != null -> discardCapture()
            else -> onBack()
        }
    }

    val captured = normalized
    if (captured != null) {
        Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                SimpleTopBar("photo", onBack = { discardCapture() })
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                confirmBitmap?.let { bitmap ->
                    if (cropping) {
                        CropOverlay(bitmap = bitmap, selection = cropRect, onSelection = { cropRect = it })
                    } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured document photo",
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
            ) {
                if (cropping) {
                    Text(
                        text = "cancel",
                        color = Grey,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f).then(
                            if (busy) Modifier else tapModifier {
                                cropping = false
                                cropRect = CropRect.FULL
                            },
                        ),
                    )
                    Text(
                        text = if (busy) "cropping…" else "done",
                        color = White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).then(
                            if (busy) Modifier else tapModifier {
                                if (cropRect == CropRect.FULL) {
                                    cropping = false
                                } else {
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.Default) {
                                            runCatching { CapturedPhoto.crop(captured, cropRect) }
                                        }
                                        busy = false
                                        result.onSuccess { bytes ->
                                            normalized?.fill(0)
                                            confirmBitmap?.recycle()
                                            confirmBitmap = runCatching { decodeForConfirm(bytes) }.getOrNull()
                                            normalized = bytes
                                            cropRect = CropRect.FULL
                                            cropping = false
                                        }.onFailure {
                                            captureFailed("Photo could not be cropped")
                                        }
                                    }
                                }
                            },
                        ),
                    )
                    return@Row
                }
                Text(
                    text = "retake",
                    color = Grey,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f).then(if (busy) Modifier else tapModifier { discardCapture() }),
                )
                Text(
                    text = "crop",
                    color = Grey,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).then(if (busy) Modifier else tapModifier { cropping = true }),
                )
                Text(
                    text = if (busy) "saving…" else "use photo",
                    color = White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).then(
                        if (busy) Modifier else tapModifier {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { PhotoStore.importBytes(context, captured) }
                                busy = false
                                result.onSuccess { imported ->
                                    discardCapture()
                                    onCaptured(imported)
                                }.onFailure { error ->
                                    captureFailed(error.message ?: "Photo could not be saved")
                                }
                            }
                        },
                    ),
                )
            }
        }
        return
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
                            val imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                android.util.Size(
                                                    CapturedPhoto.MAX_DIMENSION,
                                                    CapturedPhoto.MAX_DIMENSION,
                                                ),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                            ),
                                        )
                                        .build(),
                                )
                                .build()
                            captureRef.set(imageCapture)
                            provider.unbindAll()
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                            )
                            cameraRef.set(camera)
                            hasFlash = camera.cameraInfo.hasFlashUnit()
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
                text = if (torchEnabled) "light on" else "light",
                color = if (torchEnabled) White else Grey,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
                    .then(
                        tapModifier({
                            val enabled = !torchRef.get()
                            cameraRef.get()?.cameraControl?.enableTorch(enabled)
                            torchRef.set(enabled)
                            torchEnabled = enabled
                        }, "Toggle camera light"),
                    ),
            )
        }

        if (errorMessage == null && lifecycleOwner != null) {
            Text(
                text = if (busy) "processing…" else "capture",
                color = White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 24.dp)
                    .then(
                        if (busy) Modifier else tapModifier({
                            val imageCapture = captureRef.get() ?: return@tapModifier
                            busy = true
                            imageCapture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val rotation = image.imageInfo.rotationDegrees
                                        val buffer = image.planes[0].buffer
                                        val raw = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                        image.close()
                                        scope.launch {
                                            val result = withContext(Dispatchers.Default) {
                                                try {
                                                    runCatching { CapturedPhoto.normalize(raw, rotation) }
                                                } finally {
                                                    raw.fill(0)
                                                }
                                            }
                                            busy = false
                                            result.onSuccess { bytes ->
                                                confirmBitmap = runCatching { decodeForConfirm(bytes) }.getOrNull()
                                                normalized = bytes
                                            }.onFailure {
                                                captureFailed("Photo could not be processed")
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        captureFailed("Photo could not be taken")
                                    }
                                },
                            )
                        }, "Capture photo"),
                    ),
            )
        }
    }
}

private const val CONFIRM_MAX_DIMENSION = 1_440

/** Screen-sized preview of the normalized capture for the confirm step. */
private fun decodeForConfirm(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CONFIRM_MAX_DIMENSION) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private const val CROP_MIN_SIZE = 0.15f

/**
 * The captured photo with the crop selection over it: dimmed outside, a thin
 * frame with scanner-style corner arms, draggable by corners or as a whole.
 */
@Composable
private fun CropOverlay(bitmap: Bitmap, selection: CropRect, onSelection: (CropRect) -> Unit) {
    val currentSelection by rememberUpdatedState(selection)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / bitmap.width, boxHeight / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val imageLeft = (boxWidth - imageWidth) / 2f
        val imageTop = (boxHeight - imageHeight) / 2f

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured document photo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(bitmap) {
                var drag: CropDrag? = null
                detectDragGestures(
                    onDragStart = { position ->
                        drag = currentSelection.dragAt(
                            x = position.x - imageLeft,
                            y = position.y - imageTop,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            touchRadius = 28.dp.toPx(),
                        )
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val dx = amount.x / imageWidth
                        val dy = amount.y / imageHeight
                        when (val active = drag) {
                            is CropDrag.Corner ->
                                onSelection(currentSelection.resized(active.handle, dx, dy, CROP_MIN_SIZE))
                            CropDrag.Move -> onSelection(currentSelection.movedBy(dx, dy))
                            null -> Unit
                        }
                    },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                )
            },
        ) {
            val left = imageLeft + currentSelection.left * imageWidth
            val top = imageTop + currentSelection.top * imageHeight
            val right = imageLeft + currentSelection.right * imageWidth
            val bottom = imageTop + currentSelection.bottom * imageHeight
            val dim = Black.copy(alpha = 0.6f)
            drawRect(dim, Offset(imageLeft, imageTop), Size(imageWidth, top - imageTop))
            drawRect(dim, Offset(imageLeft, bottom), Size(imageWidth, imageTop + imageHeight - bottom))
            drawRect(dim, Offset(imageLeft, top), Size(left - imageLeft, bottom - top))
            drawRect(dim, Offset(right, top), Size(imageLeft + imageWidth - right, bottom - top))
            drawRect(
                White.copy(alpha = 0.75f),
                Offset(left, top),
                Size(right - left, bottom - top),
                style = Stroke(1.dp.toPx()),
            )
            val arm = min(24.dp.toPx(), min(right - left, bottom - top) / 3f)
            val stroke = 2.dp.toPx()
            listOf(
                Triple(Offset(left, top), Offset(arm, 0f), Offset(0f, arm)),
                Triple(Offset(right, top), Offset(-arm, 0f), Offset(0f, arm)),
                Triple(Offset(left, bottom), Offset(arm, 0f), Offset(0f, -arm)),
                Triple(Offset(right, bottom), Offset(-arm, 0f), Offset(0f, -arm)),
            ).forEach { (corner, horizontal, vertical) ->
                drawLine(White, corner, corner + horizontal, stroke)
                drawLine(White, corner, corner + vertical, stroke)
            }
        }
    }
}
