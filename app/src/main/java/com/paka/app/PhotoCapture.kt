package com.paka.app

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    val resources = LocalResources.current
    val lifecycleOwner = context as? LifecycleOwner
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var capturedBytes by remember { mutableStateOf<ByteArray?>(null) }
    val disposed = remember { AtomicBoolean(false) }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val cameraRef = remember { AtomicReference<Camera?>(null) }
    val previewRef = remember { AtomicReference<PreviewView?>(null) }
    val captureRef = remember { AtomicReference<ImageCapture?>(null) }
    val captureExecutor = remember {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "paka-photo-capture") }
    }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraGeneration = remember { AtomicLong(0) }
    val torchRef = remember { AtomicBoolean(false) }
    val lastFocusAt = remember { AtomicLong(SystemClock.elapsedRealtime()) }
    val foreground by rememberIsForeground()
    val latestCapturedBytes = rememberUpdatedState(capturedBytes)

    // A live view of someone's ID is never a sharing feature.
    ProtectSensitiveContent(true)

    fun captureFailed(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun stopCamera(resetUi: Boolean = true) {
        cameraGeneration.incrementAndGet()
        cameraRef.getAndSet(null)?.cameraControl?.enableTorch(false)
        captureRef.set(null)
        previewRef.set(null)
        providerRef.getAndSet(null)?.unbindAll()
        torchRef.set(false)
        if (resetUi && !disposed.get()) {
            torchEnabled = false
            hasFlash = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            latestCapturedBytes.value?.fill(0)
            mainExecutor.execute { stopCamera(resetUi = false) }
            captureExecutor.shutdownNow()
        }
    }

    // Close-up documents make the camera hunt just like barcodes do, so keep
    // nudging centre focus at the scanner's cadence while the preview is live.
    // A manual tap-to-focus postpones the next nudge by a full interval.
    // Keyed on foreground so the loop is cancelled while Paka is backgrounded
    // (CameraX has unbound the camera anyway) instead of waking twice a second.
    LaunchedEffect(foreground) {
        if (!foreground) return@LaunchedEffect
        while (isActive) {
            delay(FOCUS_RETRY_MS / 4)
            val now = SystemClock.elapsedRealtime()
            val idle = capturedBytes == null && !busy && now - lastFocusAt.get() >= FOCUS_RETRY_MS
            val camera = cameraRef.get()
            val preview = previewRef.get()?.takeIf { it.width > 0 && it.height > 0 }
            if (idle && camera != null && preview != null) {
                lastFocusAt.set(now)
                focusAt(camera, preview, preview.width / 2f, preview.height / 2f)
            }
        }
    }

    // CameraX turns the torch off when it unbinds on background; keep the label
    // honest by clearing the on-state so it does not read "light on" on return.
    LaunchedEffect(foreground) {
        if (!foreground && torchRef.getAndSet(false)) {
            torchEnabled = false
        }
    }

    BackHandler {
        if (!busy) onBack()
    }

    val captured = capturedBytes
    if (captured != null) {
        PhotoReviewScreen(
            title = stringResource(R.string.entry_title_photo),
            initialBytes = captured,
            cancelLabel = stringResource(R.string.capture_action_retake),
            contentDescription = stringResource(R.string.capture_cd_captured_document_photo),
            onUse = { bytes -> PhotoStore.importBytes(context, bytes) },
            onUsed = { imported ->
                capturedBytes = null
                onCaptured(imported)
            },
            onCancel = {
                errorMessage = null
                capturedBytes = null
            },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Black)) {
        if (lifecycleOwner != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    previewRef.set(previewView)
                    val generation = cameraGeneration.incrementAndGet()
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            if (disposed.get() || cameraGeneration.get() != generation) {
                                return@addListener
                            }
                            providerRef.set(provider)
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
                            if (disposed.get() || cameraGeneration.get() != generation) {
                                provider.unbindAll()
                                return@addListener
                            }
                            cameraRef.set(camera)
                            hasFlash = camera.cameraInfo.hasFlashUnit()
                            previewView.post {
                                focusAt(camera, previewView, previewView.width / 2f, previewView.height / 2f)
                            }
                        } catch (_: Exception) {
                            if (!disposed.get() && cameraGeneration.get() == generation) {
                                errorMessage = resources.getString(R.string.capture_error_camera_start)
                            }
                        }
                    }, mainExecutor)
                    previewView
                },
            )

            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { point ->
                        val camera = cameraRef.get()
                        val preview = previewRef.get()
                        if (camera != null && preview != null) {
                            lastFocusAt.set(SystemClock.elapsedRealtime())
                            focusAt(camera, preview, point.x, point.y)
                        }
                    }
                },
            )
        }

        if (lifecycleOwner == null || errorMessage != null) {
            Text(
                text = errorMessage ?: stringResource(R.string.capture_error_camera_unavailable),
                color = White,
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
            )
        }

        BackArrow(
            modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp),
            enabled = !busy,
            // The viewfinder backdrop is physically dark in both palette modes.
            color = White,
            onBack = onBack,
        )

        if (hasFlash) {
            Text(
                text = if (torchEnabled) {
                    stringResource(R.string.capture_label_light_on)
                } else {
                    stringResource(R.string.capture_label_light)
                },
                color = if (torchEnabled) White else Grey,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .then(
                        if (busy) Modifier else tapModifier({
                            val enabled = !torchRef.get()
                            cameraRef.get()?.cameraControl?.enableTorch(enabled)
                            torchRef.set(enabled)
                            torchEnabled = enabled
                        }, if (torchEnabled) {
                            stringResource(R.string.capture_cd_turn_light_off)
                        } else {
                            stringResource(R.string.capture_cd_turn_light_on)
                        }),
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }

        if (errorMessage == null && lifecycleOwner != null) {
            Text(
                text = if (busy) {
                    stringResource(R.string.capture_action_processing)
                } else {
                    stringResource(R.string.capture_action_capture)
                },
                color = White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .then(
                        if (busy) Modifier else tapModifier({
                            val imageCapture = captureRef.get() ?: return@tapModifier
                            busy = true
                            imageCapture.takePicture(
                                captureExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val rotation = image.imageInfo.rotationDegrees
                                        val raw = try {
                                            val buffer = image.planes.firstOrNull()?.buffer
                                                ?: error("Captured photo has no data")
                                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                                        } catch (_: Exception) {
                                            mainExecutor.execute {
                                                if (disposed.get()) return@execute
                                                busy = false
                                                captureFailed(
                                                    resources.getString(R.string.capture_error_process_photo),
                                                )
                                            }
                                            return
                                        } finally {
                                            image.close()
                                        }
                                        // The callback executor owns the raw frame through
                                        // normalization, so composition cancellation cannot
                                        // strand an unwiped plaintext buffer.
                                        val result = try {
                                            runCatching { CapturedPhoto.normalize(raw, rotation) }
                                        } finally {
                                            raw.fill(0)
                                        }
                                        mainExecutor.execute {
                                            if (disposed.get()) {
                                                result.getOrNull()?.fill(0)
                                                return@execute
                                            }
                                            busy = false
                                            result.onSuccess { bytes ->
                                                stopCamera()
                                                capturedBytes = bytes
                                            }.onFailure {
                                                captureFailed(
                                                    resources.getString(R.string.capture_error_process_photo),
                                                )
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        mainExecutor.execute {
                                            if (disposed.get()) return@execute
                                            busy = false
                                            captureFailed(resources.getString(R.string.capture_error_take_photo))
                                        }
                                    }
                                },
                            )
                        }, stringResource(R.string.capture_cd_capture_photo)),
                    )
                    .padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
internal fun PhotoReviewScreen(
    title: String,
    initialBytes: ByteArray,
    cancelLabel: String,
    contentDescription: String,
    onUse: suspend (ByteArray) -> Result<PhotoImport>,
    onUsed: (PhotoImport) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    var currentBytes by remember(initialBytes) { mutableStateOf(initialBytes) }
    var pendingRotations by remember(initialBytes) { mutableIntStateOf(0) }
    var cropping by remember(initialBytes) { mutableStateOf(false) }
    var cropRect by remember(initialBytes) { mutableStateOf(CropRect.FULL) }
    var busy by remember(initialBytes) { mutableStateOf(false) }
    var reviewFailed by remember(initialBytes) { mutableStateOf(false) }
    var reviewRender by remember(initialBytes) { mutableStateOf<ReviewRender?>(null) }
    val displayBitmap = reviewRender?.takeIf {
        it.source === currentBytes && it.counterClockwiseTurns == pendingRotations
    }?.bitmap
    DisposableEffect(reviewRender?.bitmap) {
        // Capture the exact bitmap owned by this effect. Reading mutable state
        // from onDispose can recycle the replacement instead of the old frame.
        val owned = reviewRender?.bitmap
        onDispose {
            owned?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    ProtectSensitiveContent(true)

    fun showError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val latestBytes = rememberUpdatedState(currentBytes)
    DisposableEffect(initialBytes) {
        onDispose { latestBytes.value.fill(0) }
    }

    // Review decoding is both bounded and off the UI thread. A private copy is
    // owned by the worker so route disposal can wipe currentBytes immediately
    // without racing a native decode that does not respond to cancellation.
    LaunchedEffect(currentBytes, pendingRotations) {
        val source = currentBytes
        val turns = pendingRotations
        reviewFailed = false
        val input = source.copyOf()
        val outcome = AtomicReference<Result<Bitmap>?>(null)
        try {
            withContext(NonCancellable + Dispatchers.Default) {
                try {
                    outcome.set(runCatching { CapturedPhoto.decodePreview(input, CONFIRM_MAX_DIMENSION, turns) })
                } finally {
                    input.fill(0)
                }
            }
            currentCoroutineContext().ensureActive()
            outcome.getAndSet(null)?.fold(
                onSuccess = { bitmap ->
                    reviewFailed = false
                    reviewRender = ReviewRender(source, turns, bitmap)
                },
                onFailure = {
                    reviewFailed = true
                    reviewRender = null
                },
            )
        } finally {
            input.fill(0)
            outcome.getAndSet(null)?.getOrNull()?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    fun navigateBack() {
        if (busy) return
        if (cropping) {
            cropping = false
            cropRect = CropRect.FULL
        } else {
            onCancel()
        }
    }
    // Always register so busy work consumes Back instead of falling through to
    // the parent route. The drawn arrow follows the exact same rule.
    BackHandler { navigateBack() }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            SimpleTopBar(title, onBack = ::navigateBack, backEnabled = !busy, color = White)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            displayBitmap?.let { bitmap ->
                if (cropping) {
                    CropOverlay(
                        bitmap = bitmap,
                        selection = cropRect,
                        onSelection = { if (!busy) cropRect = it },
                        onRotate = {
                            if (!busy) {
                                pendingRotations = (pendingRotations + 1).floorMod(4)
                                cropRect = CropRect.FULL
                            }
                        },
                    )
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } ?: Text(
                text = if (reviewFailed) {
                    stringResource(R.string.capture_error_preview_photo)
                } else {
                    stringResource(R.string.capture_status_preparing_preview)
                },
                color = Grey,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            if (cropping) {
                ReviewAction(
                    text = stringResource(R.string.capture_action_cancel),
                    color = Grey,
                    alignment = Alignment.CenterStart,
                    enabled = !busy,
                    onClick = {
                        cropping = false
                        cropRect = CropRect.FULL
                    },
                )
                ReviewAction(
                    text = stringResource(R.string.capture_action_rotate),
                    color = Grey,
                    alignment = Alignment.Center,
                    enabled = !busy && displayBitmap != null,
                    onClick = {
                        pendingRotations = (pendingRotations + 1).floorMod(4)
                        cropRect = CropRect.FULL
                    },
                )
                ReviewAction(
                    text = if (busy) {
                        stringResource(R.string.capture_action_cropping)
                    } else {
                        stringResource(R.string.capture_action_done)
                    },
                    color = White,
                    alignment = Alignment.CenterEnd,
                    enabled = !busy,
                    onClick = {
                            if (cropRect == CropRect.FULL) {
                                cropping = false
                            } else {
                                busy = true
                                scope.launch {
                                    val source = currentBytes
                                    val turns = pendingRotations
                                    val selection = cropRect
                                    var replacement: ByteArray? = null
                                    try {
                                        val result = prepareReviewBytes(
                                            source = source,
                                            counterClockwiseTurns = turns,
                                            selection = selection,
                                            transform = true,
                                        )
                                        result.onSuccess { bytes ->
                                            replacement = bytes
                                            val previous = currentBytes
                                            currentBytes = bytes
                                            replacement = null
                                            previous.fill(0)
                                            pendingRotations = 0
                                            cropRect = CropRect.FULL
                                            cropping = false
                                        }.onFailure {
                                            showError(resources.getString(R.string.capture_error_crop_photo))
                                        }
                                    } finally {
                                        replacement?.fill(0)
                                        if (isActive) busy = false
                                    }
                                }
                            }
                    },
                )
                return@Row
            }
            val photoReady = displayBitmap != null && !reviewFailed
            ReviewAction(
                text = cancelLabel,
                color = Grey,
                alignment = Alignment.CenterStart,
                enabled = !busy,
                onClick = onCancel,
            )
            ReviewAction(
                text = stringResource(R.string.capture_action_crop),
                color = Grey,
                alignment = Alignment.Center,
                enabled = !busy && photoReady,
                onClick = { cropping = true },
            )
            ReviewAction(
                text = if (busy) {
                    stringResource(R.string.capture_action_saving)
                } else {
                    stringResource(R.string.capture_action_use_photo)
                },
                color = if (photoReady) White else Grey,
                alignment = Alignment.CenterEnd,
                enabled = !busy && photoReady,
                onClick = {
                        busy = true
                        scope.launch {
                            val source = currentBytes
                            val turns = pendingRotations
                            var prepared: ByteArray? = null
                            val imported = AtomicReference<Result<PhotoImport>?>(null)
                            try {
                                val preparedResult = prepareReviewBytes(
                                    source = source,
                                    counterClockwiseTurns = turns,
                                    selection = CropRect.FULL,
                                    // Untouched picker imports retain their exact
                                    // encrypted bytes. Any queued edit is baked
                                    // into one bounded JPEG.
                                    transform = turns.floorMod(FULL_TURN_QUARTERS) != 0,
                                )
                                val bytes = preparedResult.getOrElse {
                                    showError(resources.getString(R.string.capture_error_prepare_photo))
                                    return@launch
                                }
                                prepared = bytes
                                withContext(NonCancellable + Dispatchers.IO) {
                                    imported.set(runCatching { onUse(bytes).getOrThrow() })
                                }
                                currentCoroutineContext().ensureActive()
                                val result = imported.getAndSet(null)
                                    ?: Result.failure(IllegalStateException("Photo import did not finish"))
                                busy = false
                                result.onSuccess(onUsed).onFailure {
                                    showError(resources.getString(R.string.capture_error_save_photo))
                                }
                            } finally {
                                prepared?.fill(0)
                                val abandoned = imported.getAndSet(null)?.getOrNull()
                                if (abandoned?.created == true) {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        PhotoStore.delete(context, abandoned.page.documentId)
                                    }
                                }
                                if (isActive) busy = false
                            }
                        }
                },
            )
        }
    }
}

private const val CONFIRM_MAX_DIMENSION = 1_440
private const val FULL_TURN_QUARTERS = 4

private data class ReviewRender(
    val source: ByteArray,
    val counterClockwiseTurns: Int,
    val bitmap: Bitmap,
)

@Composable
private fun RowScope.ReviewAction(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    alignment: Alignment,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .then(if (enabled) tapModifier(onClick) else Modifier)
            .heightIn(min = 48.dp),
        contentAlignment = alignment,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = when (alignment) {
                Alignment.CenterEnd -> TextAlign.End
                Alignment.Center -> TextAlign.Center
                else -> TextAlign.Start
            },
        )
    }
}

/** Native bitmap work is not cancellable; this function retains and wipes every output until transfer. */
private suspend fun prepareReviewBytes(
    source: ByteArray,
    counterClockwiseTurns: Int,
    selection: CropRect,
    transform: Boolean,
): Result<ByteArray> {
    val input = source.copyOf()
    val outcome = AtomicReference<Result<ByteArray>?>(null)
    return try {
        withContext(NonCancellable + Dispatchers.Default) {
            try {
                outcome.set(
                    runCatching {
                        if (transform) {
                            CapturedPhoto.applyEdits(input, counterClockwiseTurns, selection)
                        } else {
                            input.copyOf()
                        }
                    },
                )
            } finally {
                input.fill(0)
            }
        }
        currentCoroutineContext().ensureActive()
        outcome.getAndSet(null) ?: Result.failure(IllegalStateException("Photo preparation did not finish"))
    } finally {
        input.fill(0)
        outcome.getAndSet(null)?.getOrNull()?.fill(0)
    }
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private const val CROP_MIN_SIZE = 0.15f
private const val ACCESSIBLE_CROP_STEP = 0.05f
private val CROP_HANDLE_TOUCH_RADIUS = 64.dp
private val CROP_TOP_HANDLE_TOUCH_RADIUS = 88.dp

/**
 * The captured photo with the crop selection over it: dimmed outside, a thin
 * frame with scanner-style corner arms, draggable by corners or as a whole.
 */
@Composable
private fun CropOverlay(
    bitmap: Bitmap,
    selection: CropRect,
    onSelection: (CropRect) -> Unit,
    onRotate: () -> Unit,
) {
    val currentSelection by rememberUpdatedState(selection)
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val cropDescription = stringResource(R.string.capture_cd_crop_area)
    val rotateAction = stringResource(R.string.capture_action_rotate)
    val resetAction = stringResource(R.string.capture_action_reset_crop)
    val smallerAction = stringResource(R.string.capture_action_crop_smaller)
    val largerAction = stringResource(R.string.capture_action_crop_larger)
    val moveLeftAction = stringResource(R.string.capture_action_crop_left)
    val moveRightAction = stringResource(R.string.capture_action_crop_right)
    val moveUpAction = stringResource(R.string.capture_action_crop_up)
    val moveDownAction = stringResource(R.string.capture_action_crop_down)
    fun applyAccessibleCrop(next: CropRect) {
        if (next == currentSelection) return
        performPakaHaptic(context, haptics)
        onSelection(next)
    }
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
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(
            modifier = Modifier.fillMaxSize()
                .semantics {
                    contentDescription = cropDescription
                    customActions = buildList {
                        add(
                            CustomAccessibilityAction(rotateAction) {
                                performPakaHaptic(context, haptics)
                                onRotate()
                                true
                            },
                        )
                        if (
                            currentSelection.width > CROP_MIN_SIZE + ACCESSIBLE_CROP_STEP * 2 &&
                            currentSelection.height > CROP_MIN_SIZE + ACCESSIBLE_CROP_STEP * 2
                        ) {
                            add(
                                CustomAccessibilityAction(smallerAction) {
                                    applyAccessibleCrop(currentSelection.insetBy(ACCESSIBLE_CROP_STEP))
                                    true
                                },
                            )
                        }
                        if (currentSelection != CropRect.FULL) {
                            add(
                                CustomAccessibilityAction(largerAction) {
                                    applyAccessibleCrop(currentSelection.expandedBy(ACCESSIBLE_CROP_STEP))
                                    true
                                },
                            )
                        }
                        if (currentSelection.left > 0f) {
                            add(
                                CustomAccessibilityAction(moveLeftAction) {
                                    applyAccessibleCrop(currentSelection.movedBy(-ACCESSIBLE_CROP_STEP, 0f))
                                    true
                                },
                            )
                        }
                        if (currentSelection.right < 1f) {
                            add(
                                CustomAccessibilityAction(moveRightAction) {
                                    applyAccessibleCrop(currentSelection.movedBy(ACCESSIBLE_CROP_STEP, 0f))
                                    true
                                },
                            )
                        }
                        if (currentSelection.top > 0f) {
                            add(
                                CustomAccessibilityAction(moveUpAction) {
                                    applyAccessibleCrop(currentSelection.movedBy(0f, -ACCESSIBLE_CROP_STEP))
                                    true
                                },
                            )
                        }
                        if (currentSelection.bottom < 1f) {
                            add(
                                CustomAccessibilityAction(moveDownAction) {
                                    applyAccessibleCrop(currentSelection.movedBy(0f, ACCESSIBLE_CROP_STEP))
                                    true
                                },
                            )
                        }
                        if (currentSelection != CropRect.FULL) {
                            add(
                                CustomAccessibilityAction(resetAction) {
                                    performPakaHaptic(context, haptics)
                                    onSelection(CropRect.FULL)
                                    true
                                },
                            )
                        }
                    }
                }
                .pointerInput(bitmap) {
                var drag: CropDrag? = null
                detectDragGestures(
                    onDragStart = { position ->
                        drag = currentSelection.dragAt(
                            x = position.x - imageLeft,
                            y = position.y - imageTop,
                            target = CropDragTarget(
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                touchRadius = CROP_HANDLE_TOUCH_RADIUS.toPx(),
                                topTouchRadius = CROP_TOP_HANDLE_TOUCH_RADIUS.toPx(),
                            ),
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

private fun CropRect.insetBy(amount: Float): CropRect {
    val horizontal = amount.coerceAtMost((width - CROP_MIN_SIZE) / 2f)
    val vertical = amount.coerceAtMost((height - CROP_MIN_SIZE) / 2f)
    return CropRect(
        left = left + horizontal,
        top = top + vertical,
        right = right - horizontal,
        bottom = bottom - vertical,
    )
}

private fun CropRect.expandedBy(amount: Float): CropRect = CropRect(
    left = (left - amount).coerceAtLeast(0f),
    top = (top - amount).coerceAtLeast(0f),
    right = (right + amount).coerceAtMost(1f),
    bottom = (bottom + amount).coerceAtMost(1f),
)
