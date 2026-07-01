package com.paka.app

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ScanResult(val data: String, val format: PakaFormat)

@Composable
fun ScanScreen(onScanned: (ScanResult) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val reader = remember {
        BarcodeReader(
            BarcodeReader.Options(
                formats = emptySet(),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
                tryDownscale = true,
            ),
        )
    }
    val handled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val executor = remember {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "paka-barcode-scan") }
    }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            ContextCompat.getMainExecutor(context).execute { providerRef.getAndSet(null)?.unbindAll() }
            executor.shutdownNow()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Black)) {
        if (lifecycleOwner != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
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
                                .build()
                                .also { imageAnalysis ->
                                    imageAnalysis.setAnalyzer(executor) { image ->
                                        try {
                                            if (!handled.get()) {
                                                val result = reader.read(image).firstOrNull { it.text != null || it.bytes != null }
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
                                                    previewView.post { onScanned(ScanResult(data, format)) }
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
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        } catch (_: Exception) {
                            errorMessage = "Camera could not be started"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
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
    }
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
