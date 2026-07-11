package com.paka.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private sealed interface ManualCardItem {
    data object Name : ManualCardItem
    data object Data : ManualCardItem
    data class Format(val format: PakaFormat) : ManualCardItem
    data object PdfDocument : ManualCardItem
    data object PhotoDocument : ManualCardItem
}

private enum class ManualCodeItem { NAME, ACCOUNT, SECRET }
private enum class ManualCardField { NAME, DATA }
private enum class PhotoSourceAction { TAKE, CHOOSE }
internal enum class TextEntryKeyboardProfile { NORMAL, EXACT, PASSWORD }

private data class PendingPhotoBatch(
    val bytes: List<ByteArray>,
    val reviewed: List<PhotoImport> = emptyList(),
)

private val MANUAL_FORMATS = listOf(
    PakaFormat.QR, PakaFormat.AZTEC, PakaFormat.PDF417, PakaFormat.DATA_MATRIX,
    PakaFormat.CODE128, PakaFormat.CODE39, PakaFormat.CODE93, PakaFormat.CODABAR,
    PakaFormat.ITF, PakaFormat.EAN13, PakaFormat.EAN8, PakaFormat.UPCA, PakaFormat.UPCE,
    PakaFormat.DATABAR_EXPANDED,
)

@Composable
internal fun ManualCardScreen(documentImportsEnabled: Boolean, onSave: (Card) -> Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var data by remember { mutableStateOf("") }
    var format by remember { mutableStateOf<PakaFormat?>(PakaFormat.QR) }
    var pdfImport by remember { mutableStateOf<PdfImport?>(null) }
    var photoMode by remember { mutableStateOf(false) }
    var photoImports by remember { mutableStateOf<List<PhotoImport>>(emptyList()) }
    var photoSourceMenu by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var photoReviewBatch by remember { mutableStateOf<PendingPhotoBatch?>(null) }
    var pdfChecking by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }
    var editingField by remember { mutableStateOf<ManualCardField?>(null) }
    // Route disposal can happen synchronously inside onSave before Compose has
    // observed another state write. Keep ownership handoff outside snapshots.
    val contentTransferred = remember { AtomicBoolean(false) }
    val trimmedData = data.trim()
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val standardRenderTargetWidth = with(density) {
        (configuration.screenWidthDp.dp - 32.dp).roundToPx()
    }.coerceAtLeast(240)
    val renderTargetWidth = format?.let { selected ->
        BarcodeDisplay.targetWidthPx(selected, screenWidthPx, standardRenderTargetWidth)
    } ?: standardRenderTargetWidth
    val renderKey = "${format?.name}:$renderTargetWidth:$trimmedData"
    val basicValidationError = format?.let { selected ->
        trimmedData.takeIf { it.isNotBlank() }?.let { Barcodes.validationError(selected, it) }
    }?.resolve(context)
    var renderCheck by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val renderable = renderCheck?.takeIf { it.first == renderKey }?.second
    val validating = format != null && trimmedData.isNotBlank() && basicValidationError == null && renderable == null
    val exactRenderError = stringResource(R.string.entry_error_code_cannot_render_exactly)
    val validationError = basicValidationError ?: if (renderable == false) exactRenderError else null
    val canSave = name.isNotBlank() && !pdfChecking && if (format == null) {
        if (photoMode) photoImports.size in 1..2 && pdfError == null else pdfImport != null && pdfError == null
    } else {
        trimmedData.isNotBlank() && validationError == null && renderable == true
    }
    fun deleteAbandonedImports(
        pdfs: Collection<PdfImport> = emptyList(),
        photos: Collection<PhotoImport> = emptyList(),
    ) {
        StoreWriteCoordinator.deleteUnreferencedImports(
            context = context,
            pdfIds = pdfs.filter(PdfImport::created).mapTo(hashSetOf(), PdfImport::documentId),
            photoIds = photos.filter(PhotoImport::created).mapTo(hashSetOf()) { it.page.documentId },
        )
    }
    val latestPdfImport = rememberUpdatedState(pdfImport)
    val latestPhotoImports = rememberUpdatedState(photoImports)
    val latestReviewBatch = rememberUpdatedState(photoReviewBatch)
    DisposableEffect(Unit) {
        onDispose {
            val batch = latestReviewBatch.value
            batch?.bytes?.forEach { it.fill(0) }
            if (!contentTransferred.get()) {
                StoreWriteCoordinator.deleteUnreferencedImports(
                    context = context,
                    pdfIds = listOfNotNull(latestPdfImport.value)
                        .filter(PdfImport::created)
                        .mapTo(hashSetOf(), PdfImport::documentId),
                    photoIds = (latestPhotoImports.value + batch?.reviewed.orEmpty())
                        .filter(PhotoImport::created)
                        .mapTo(hashSetOf()) { it.page.documentId },
                )
            }
        }
    }
    val items = remember {
        // QR, PDF, and photo documents share the first page; other formats follow.
        listOf(
            ManualCardItem.Name,
            ManualCardItem.Data,
            ManualCardItem.Format(PakaFormat.QR),
            ManualCardItem.PdfDocument,
            ManualCardItem.PhotoDocument,
        ) + MANUAL_FORMATS.filterNot { it == PakaFormat.QR }.map(ManualCardItem::Format)
    }
    val discardAndBack = {
        val pendingPdfs = listOfNotNull(pdfImport)
        val pendingPhotos = photoImports.toMutableList()
        photoReviewBatch?.let { batch ->
            batch.bytes.forEach { it.fill(0) }
            pendingPhotos += batch.reviewed
        }
        deleteAbandonedImports(pendingPdfs, pendingPhotos)
        onBack()
    }
    BackHandler { discardAndBack() }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        context.setPakaExternalFlowActive(false)
        if (uri != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                pdfError = resources.getString(R.string.entry_error_pdf_requires_android_11)
            } else {
                scope.launch {
                    pdfChecking = true
                    pdfError = null
                    val result = importPdfOwned(context, uri)
                    pdfChecking = false
                    result.onSuccess { imported ->
                        pdfImport?.takeIf { it.created && it.documentId != imported.documentId }?.let { orphan ->
                            deleteAbandonedImports(pdfs = listOf(orphan))
                        }
                        pdfImport = imported
                    }.onFailure { error ->
                        pdfError = when (error) {
                            is SecurityException -> resources.getString(R.string.entry_error_password_pdf_unsupported)
                            else -> resources.getString(R.string.entry_error_pdf_open)
                        }
                    }
                }
            }
        }
    }

    // Shared by the document picker and the in-app camera: appends a second
    // side or replaces the selection, deleting freshly created orphans.
    fun mergePhotoImports(imported: List<PhotoImport>) {
        val next = if (photoImports.size == 1 && imported.size == 1 &&
            photoImports.first().page.documentId != imported.first().page.documentId
        ) {
            photoImports + imported
        } else {
            imported
        }
        val priorCreatedIds = photoImports.filter { it.created }.map { it.page.documentId }.toSet()
        val normalized = next.map { importedPhoto ->
            if (importedPhoto.page.documentId in priorCreatedIds && !importedPhoto.created) {
                importedPhoto.copy(created = true)
            } else importedPhoto
        }
        val keptIds = normalized.map { it.page.documentId }.toSet()
        deleteAbandonedImports(
            photos = photoImports.filter { it.created && it.page.documentId !in keptIds },
        )
        photoImports = normalized
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        context.setPakaExternalFlowActive(false)
        if (uris.isNotEmpty()) {
            scope.launch {
                pdfChecking = true
                pdfError = null
                val result = readPhotoBatchOwned(context, uris)
                pdfChecking = false
                result.onSuccess { picked ->
                    if (picked.isNotEmpty()) photoReviewBatch = PendingPhotoBatch(picked)
                }.onFailure {
                    pdfError = resources.getString(R.string.entry_error_photos_open)
                }
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            photoSourceMenu = false
            capturing = true
        } else {
            Toast.makeText(
                context,
                resources.getString(R.string.entry_error_camera_permission),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val startCapture = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (granted == PackageManager.PERMISSION_GRANTED) {
            photoSourceMenu = false
            capturing = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (capturing) {
        PhotoCaptureScreen(
            onCaptured = { imported ->
                mergePhotoImports(listOf(imported))
                capturing = false
            },
            onBack = { capturing = false },
        )
        return
    }

    val reviewBatch = photoReviewBatch
    if (reviewBatch != null) {
        val reviewIndex = reviewBatch.reviewed.size
        val currentBytes = reviewBatch.bytes.getOrNull(reviewIndex)
        if (currentBytes != null) {
            fun cancelReviewBatch() {
                reviewBatch.bytes.forEach { it.fill(0) }
                deleteAbandonedImports(photos = reviewBatch.reviewed)
                photoReviewBatch = null
            }
            val title = when {
                reviewBatch.bytes.size > 1 && reviewIndex == 0 -> stringResource(R.string.entry_title_front_side)
                reviewBatch.bytes.size > 1 || photoImports.size == 1 -> stringResource(R.string.entry_title_back_side)
                else -> stringResource(R.string.entry_title_photo)
            }
            PhotoReviewScreen(
                title = title,
                initialBytes = currentBytes,
                cancelLabel = stringResource(R.string.entry_action_cancel),
                contentDescription = stringResource(R.string.entry_cd_selected_document_photo),
                onUse = { bytes -> PhotoStore.importBytes(context, bytes) },
                onUsed = { imported ->
                    val reviewed = reviewBatch.reviewed + imported
                    if (reviewed.size >= reviewBatch.bytes.size) {
                        mergePhotoImports(reviewed)
                        photoReviewBatch = null
                    } else {
                        photoReviewBatch = reviewBatch.copy(reviewed = reviewed)
                    }
                },
                onCancel = ::cancelReviewBatch,
            )
            return
        } else {
            photoReviewBatch = null
        }
    }

    if (photoSourceMenu) {
        PhotoSourceScreen(
            secondSide = photoImports.size == 1,
            onTake = startCapture,
            onChoose = {
                photoSourceMenu = false
                context.setPakaExternalFlowActive(true)
                photoPicker.launch(arrayOf("image/*"))
            },
            onBack = { photoSourceMenu = false },
        )
        return
    }

    editingField?.let { field ->
        TextEntryScreen(
            title = if (field == ManualCardField.NAME) {
                stringResource(R.string.entry_title_name)
            } else {
                stringResource(R.string.entry_title_data)
            },
            initial = if (field == ManualCardField.NAME) name else data,
            keyboardProfile = if (field == ManualCardField.DATA) {
                TextEntryKeyboardProfile.EXACT
            } else {
                TextEntryKeyboardProfile.NORMAL
            },
            onSave = { value ->
                if (field == ManualCardField.NAME) name = value else data = value
                editingField = null
            },
            onBack = { editingField = null },
        )
        return
    }

    LaunchedEffect(renderKey, basicValidationError) {
        val selected = format ?: return@LaunchedEffect
        if (trimmedData.isBlank() || basicValidationError != null) return@LaunchedEffect
        delay(120)
        val verified = withContext(Dispatchers.Default) {
            val bitmap = Barcodes.generate(selected, trimmedData, renderTargetWidth)
            (bitmap != null).also { bitmap?.recycle() }
        }
        renderCheck = renderKey to verified
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(stringResource(R.string.entry_title_pass), discardAndBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(items) { item ->
                when (item) {
                    ManualCardItem.Name -> ManualEntryRow(
                        stringResource(R.string.entry_label_name),
                        name,
                        stringResource(R.string.entry_placeholder_pass_name),
                    ) {
                        editingField = ManualCardField.NAME
                    }
                    ManualCardItem.Data -> if (format == null) {
                        EntryChoiceRow(
                            label = if (pdfChecking) {
                                stringResource(R.string.entry_action_checking)
                            } else if (photoMode) {
                                when (photoImports.size) {
                                    1 -> stringResource(R.string.entry_action_add_back_photo)
                                    2 -> stringResource(R.string.entry_action_change_photos)
                                    else -> stringResource(R.string.entry_action_choose_photos)
                                }
                            } else stringResource(R.string.entry_action_choose_pdf),
                            trailing = if (photoMode) {
                                photoImports.takeIf { it.isNotEmpty() }?.let {
                                    stringResource(R.string.entry_photo_side_count_short, it.size)
                                }
                            } else {
                                pdfImport?.let {
                                    stringResource(R.string.entry_pdf_page_count_short, it.pageCount)
                                }
                            },
                            enabled = !pdfChecking,
                            onClick = {
                                if (photoMode) {
                                    photoSourceMenu = true
                                } else {
                                    context.setPakaExternalFlowActive(true)
                                    pdfPicker.launch(arrayOf("application/pdf"))
                                }
                            },
                        )
                    } else {
                        ManualEntryRow(
                            stringResource(R.string.entry_label_data),
                            data,
                            stringResource(R.string.entry_placeholder_code_content),
                        ) {
                            editingField = ManualCardField.DATA
                        }
                    }
                    is ManualCardItem.Format -> {
                        val f = item.format
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (f == format) Modifier else tapModifier { format = f })
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = f.label(),
                                color = if (f == format) Palette.foreground else Palette.dim,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.weight(1f),
                            )
                            if (f == format) {
                                Text(
                                    stringResource(R.string.entry_label_selected),
                                    color = Palette.dim,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Light,
                                )
                            }
                        }
                    }
                    ManualCardItem.PdfDocument -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                tapModifier {
                                    if (!documentImportsEnabled) {
                                        // Imported blobs live in the real store; a demo pass
                                        // cannot reference one without orphaning it later.
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.entry_toast_pdf_demo_disabled),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@tapModifier
                                    }
                                    format = null
                                    photoMode = false
                                    pdfError = null
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        if (!pdfChecking) {
                                            context.setPakaExternalFlowActive(true)
                                            pdfPicker.launch(arrayOf("application/pdf"))
                                        }
                                    } else {
                                        pdfError = resources.getString(R.string.entry_error_pdf_requires_android_11)
                                    }
                                }
                            )
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.entry_label_pdf_document),
                            color = if (format == null && !photoMode) Palette.foreground else Palette.dim,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                        )
                        if (format == null && !photoMode) {
                            Text(
                                stringResource(R.string.entry_label_selected),
                                color = Palette.dim,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                    ManualCardItem.PhotoDocument -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                tapModifier {
                                    if (!documentImportsEnabled) {
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.entry_toast_photo_demo_disabled),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@tapModifier
                                    }
                                    format = null
                                    photoMode = true
                                    pdfError = null
                                    if (!pdfChecking) photoSourceMenu = true
                                },
                            )
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.entry_label_photo_pass),
                            color = if (format == null && photoMode) Palette.foreground else Palette.dim,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                        )
                        if (format == null && photoMode) {
                            Text(
                                stringResource(R.string.entry_label_selected),
                                color = Palette.dim,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                }
            }
        }
        val actionError = if (format == null) pdfError else validationError
        val statusText = actionError ?: if (validating || pdfChecking) {
            stringResource(R.string.entry_action_checking)
        } else {
            null
        }
        EntryBottomAction(
            status = statusText,
            enabled = canSave,
            onSave = {
                val selected = format
                if (selected == null) {
                    if (photoMode) {
                        deleteAbandonedImports(pdfs = listOfNotNull(pdfImport))
                        contentTransferred.set(
                            onSave(
                                Card(
                                    name = name.trim(),
                                    content = PassContent.Photos(photoImports.map(PhotoImport::page)),
                                ),
                            ),
                        )
                    } else {
                        deleteAbandonedImports(photos = photoImports)
                        val imported = checkNotNull(pdfImport)
                        contentTransferred.set(
                            onSave(
                                Card(
                                    name = name.trim(),
                                    content = PassContent.Pdf(imported.documentId, imported.pageCount),
                                ),
                            ),
                        )
                    }
                } else {
                    deleteAbandonedImports(listOfNotNull(pdfImport), photoImports)
                    contentTransferred.set(
                        onSave(Card(name = name.trim(), data = trimmedData, format = selected)),
                    )
                }
            },
        )
    }
}

@Composable
private fun PhotoSourceScreen(secondSide: Boolean, onTake: () -> Unit, onChoose: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(
            if (secondSide) {
                stringResource(R.string.entry_title_back_side)
            } else {
                stringResource(R.string.entry_title_photos)
            },
            onBack,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(PhotoSourceAction.entries) { action ->
                val label = when (action) {
                    PhotoSourceAction.TAKE -> stringResource(R.string.entry_action_take_photo)
                    PhotoSourceAction.CHOOSE -> stringResource(R.string.entry_action_choose_photos)
                }
                AutoFitText(
                    label,
                    color = Palette.foreground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            tapModifier {
                            when (action) {
                                PhotoSourceAction.TAKE -> onTake()
                                PhotoSourceAction.CHOOSE -> onChoose()
                            }
                            },
                        )
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
            }
            Text(
                stringResource(R.string.entry_photo_source_privacy),
                color = Palette.dim,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.BottomStart).padding(end = 14.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
internal fun ManualCodeScreen(onSave: (OtpAccount) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var issuer by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<ManualCodeItem?>(null) }
    val draft = OtpAccount(
        issuer = issuer.trim(),
        account = account.trim(),
        secret = secret.replace(" ", "").trim(),
    )
    val validationError = if (issuer.isBlank() || secret.isBlank()) {
        null
    } else {
        Totp.validationError(draft)?.resolve(context)
    }
    val canSave = issuer.isNotBlank() && secret.isNotBlank() && validationError == null

    editingItem?.let { item ->
        TextEntryScreen(
            title = when (item) {
                ManualCodeItem.NAME -> stringResource(R.string.entry_label_service)
                ManualCodeItem.ACCOUNT -> stringResource(R.string.entry_label_account)
                ManualCodeItem.SECRET -> stringResource(R.string.entry_label_secret)
            },
            initial = when (item) {
                ManualCodeItem.NAME -> issuer
                ManualCodeItem.ACCOUNT -> account
                ManualCodeItem.SECRET -> secret
            },
            visualTransformation = if (item == ManualCodeItem.SECRET) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardProfile = if (item == ManualCodeItem.SECRET) {
                TextEntryKeyboardProfile.PASSWORD
            } else {
                TextEntryKeyboardProfile.NORMAL
            },
            allowBlank = item == ManualCodeItem.ACCOUNT,
            onSave = { value ->
                when (item) {
                    ManualCodeItem.NAME -> issuer = value
                    ManualCodeItem.ACCOUNT -> account = value
                    ManualCodeItem.SECRET -> secret = value
                }
                editingItem = null
            },
            onBack = { editingItem = null },
        )
        return
    }
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(stringResource(R.string.entry_title_code), onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(ManualCodeItem.entries) { item ->
                when (item) {
                    ManualCodeItem.NAME -> ManualEntryRow(
                        stringResource(R.string.entry_label_service),
                        issuer,
                        stringResource(R.string.entry_placeholder_issuer),
                    ) { editingItem = item }
                    ManualCodeItem.ACCOUNT -> ManualEntryRow(
                        stringResource(R.string.entry_label_account),
                        account,
                        stringResource(R.string.entry_placeholder_optional),
                    ) { editingItem = item }
                    ManualCodeItem.SECRET -> ManualEntryRow(
                        stringResource(R.string.entry_label_secret),
                        if (secret.isEmpty()) "" else "•".repeat(min(secret.length, 16)),
                        stringResource(R.string.entry_placeholder_base32_key),
                    ) { editingItem = item }
                }
            }
        }
        EntryBottomAction(
            status = validationError,
            enabled = canSave,
            onSave = { onSave(draft) },
        )
    }
}

@Composable
internal fun TextEntryScreen(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    allowBlank: Boolean = false,
    singleLine: Boolean = true,
    // Passphrases keep their exact characters; names and notes are trimmed.
    trimOnSave: Boolean = true,
    keyboardProfile: TextEntryKeyboardProfile = if (visualTransformation is PasswordVisualTransformation) {
        TextEntryKeyboardProfile.PASSWORD
    } else {
        TextEntryKeyboardProfile.NORMAL
    },
    fieldLabel: String = title,
) {
    var text by remember { mutableStateOf(initial) }
    val canSave = allowBlank || text.isNotBlank()
    val savedText = { if (trimOnSave) text.trim() else text }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeAction = if (singleLine) ImeAction.Done else ImeAction.Default
    val keyboardOptions = when (keyboardProfile) {
        TextEntryKeyboardProfile.NORMAL -> KeyboardOptions(imeAction = imeAction)
        TextEntryKeyboardProfile.EXACT -> KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = imeAction,
        )
        TextEntryKeyboardProfile.PASSWORD -> KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        )
    }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(title, onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    textStyle = TextStyle(
                        color = Palette.foreground,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Normal,
                    ).withPakaFont(),
                    cursorBrush = SolidColor(Palette.foreground),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSave) {
                                keyboard?.hide()
                                onSave(savedText())
                            }
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (singleLine) Modifier else Modifier
                                .fillMaxHeight(0.72f)
                                .heightIn(max = 180.dp),
                        )
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = fieldLabel },
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.foreground))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canSave) tapModifier {
                        keyboard?.hide()
                        onSave(savedText())
                    } else Modifier,
                )
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(R.string.entry_action_save),
                color = if (canSave) Palette.foreground else Palette.dim,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun EntryChoiceRow(
    label: String,
    trailing: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) tapModifier(onClick) else Modifier)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoFitText(
            text = label,
            color = if (enabled) Palette.foreground else Palette.dim,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, color = Palette.dim, fontSize = 18.sp, fontWeight = FontWeight.Light)
        }
    }
}


@Composable
private fun EntryBottomAction(status: String?, enabled: Boolean, onSave: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = status.orEmpty(),
            color = Palette.dim,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled) tapModifier(onSave) else Modifier)
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(R.string.entry_action_save),
                color = if (enabled) Palette.foreground else Palette.dim,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    // Explicit line height: field rows must fit a fifth of the paged viewport
    // on the Light Phone III's short screen (see PagedList).
    Text(
        text,
        color = Palette.dim,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 2.sp,
    )
}

@Composable
internal fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Palette.foreground, fontSize = 20.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
internal fun ManualEntryRow(label: String, value: String, placeholder: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(tapModifier(onClick))
            .heightIn(min = 48.dp),
    ) {
        FieldLabel(label)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value.ifEmpty { placeholder },
            color = if (value.isEmpty()) Palette.dim else Palette.foreground,
            fontSize = 20.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.dim))
    }
}

@Composable
internal fun ReferenceEntryRow(label: String, reference: PassReference?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(tapModifier(onClick))
            .heightIn(min = 48.dp),
    ) {
        FieldLabel(label)
        Spacer(Modifier.height(3.dp))
        Text(
            text = reference?.name ?: stringResource(R.string.entry_reference_add_file),
            color = if (reference == null) Palette.dim else Palette.foreground,
            fontSize = 20.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (reference != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.entry_reference_external_unencrypted),
                color = Palette.dim,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Light,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.dim))
    }
}
