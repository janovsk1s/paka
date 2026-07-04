package com.paka.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private val MANUAL_FORMATS = listOf(
    PakaFormat.QR, PakaFormat.AZTEC, PakaFormat.PDF417, PakaFormat.DATA_MATRIX,
    PakaFormat.CODE128, PakaFormat.CODE39, PakaFormat.CODE93, PakaFormat.CODABAR,
    PakaFormat.ITF, PakaFormat.EAN13, PakaFormat.EAN8, PakaFormat.UPCA, PakaFormat.UPCE,
    PakaFormat.DATABAR_EXPANDED,
)

internal fun PakaFormat.label(): String = when (this) {
    PakaFormat.QR -> "QR"
    PakaFormat.AZTEC -> "Aztec"
    PakaFormat.PDF417 -> "PDF417"
    PakaFormat.DATA_MATRIX -> "Data Matrix"
    PakaFormat.CODE128 -> "Code 128"
    PakaFormat.CODE39 -> "Code 39"
    PakaFormat.CODE93 -> "Code 93"
    PakaFormat.CODABAR -> "Codabar"
    PakaFormat.ITF -> "ITF"
    PakaFormat.EAN13 -> "EAN-13"
    PakaFormat.EAN8 -> "EAN-8"
    PakaFormat.UPCA -> "UPC-A"
    PakaFormat.UPCE -> "UPC-E"
    PakaFormat.DATABAR_EXPANDED -> "GS1 DataBar"
}

@Composable
internal fun ManualCardScreen(documentImportsEnabled: Boolean, onSave: (Card) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var data by remember { mutableStateOf("") }
    var format by remember { mutableStateOf<PakaFormat?>(PakaFormat.QR) }
    var pdfImport by remember { mutableStateOf<PdfImport?>(null) }
    var photoMode by remember { mutableStateOf(false) }
    var photoImports by remember { mutableStateOf<List<PhotoImport>>(emptyList()) }
    var photoSourceMenu by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var pdfChecking by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }
    var editingField by remember { mutableStateOf<ManualCardField?>(null) }
    val trimmedData = data.trim()
    val renderKey = "${format?.name}:$trimmedData"
    val basicValidationError = format?.let { selected ->
        trimmedData.takeIf { it.isNotBlank() }?.let { Barcodes.validationError(selected, it) }
    }
    var renderCheck by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val renderable = renderCheck?.takeIf { it.first == renderKey }?.second
    val validating = format != null && trimmedData.isNotBlank() && basicValidationError == null && renderable == null
    val validationError = basicValidationError ?: if (renderable == false) "Code cannot be rendered exactly" else null
    val canSave = name.isNotBlank() && !pdfChecking && if (format == null) {
        if (photoMode) photoImports.size in 1..2 && pdfError == null else pdfImport != null && pdfError == null
    } else {
        trimmedData.isNotBlank() && validationError == null && renderable == true
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
        pdfImport?.takeIf { it.created }?.let { orphan ->
            scope.launch(Dispatchers.IO) { PdfStore.delete(context, orphan.documentId) }
        }
        photoImports.filter { it.created }.forEach { orphan ->
            scope.launch(Dispatchers.IO) { PhotoStore.delete(context, orphan.page.documentId) }
        }
        onBack()
    }
    BackHandler { discardAndBack() }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        context.setPakaExternalFlowActive(false)
        if (uri != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                pdfError = "PDF passes require Android 11 or newer"
            } else {
                scope.launch {
                    pdfChecking = true
                    pdfError = null
                    val result = withContext(Dispatchers.IO) { PdfStore.import(context, uri) }
                    pdfChecking = false
                    result.onSuccess { imported ->
                        pdfImport?.takeIf { it.created && it.documentId != imported.documentId }?.let { orphan ->
                            withContext(Dispatchers.IO) { PdfStore.delete(context, orphan.documentId) }
                        }
                        pdfImport = imported
                    }.onFailure { error ->
                        pdfError = when (error) {
                            is SecurityException -> "Password-protected PDFs are not supported"
                            else -> error.message ?: "PDF could not be opened"
                        }
                    }
                }
            }
        }
    }

    // Shared by the document picker and the in-app camera: appends a second
    // side or replaces the selection, deleting freshly created orphans.
    suspend fun mergePhotoImports(imported: List<PhotoImport>) {
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
        photoImports.filter { it.created && it.page.documentId !in keptIds }.forEach { orphan ->
            withContext(Dispatchers.IO) { PhotoStore.delete(context, orphan.page.documentId) }
        }
        photoImports = normalized
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        context.setPakaExternalFlowActive(false)
        if (uris.isNotEmpty()) {
            scope.launch {
                pdfChecking = true
                pdfError = null
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val imported = mutableListOf<PhotoImport>()
                        try {
                            uris.distinctBy(Uri::toString).take(2).forEach { uri ->
                                imported += PhotoStore.import(context, uri).getOrThrow()
                            }
                            imported.toList()
                        } catch (error: Throwable) {
                            imported.filter { it.created }.forEach { PhotoStore.delete(context, it.page.documentId) }
                            throw error
                        }
                    }
                }
                pdfChecking = false
                result.onSuccess { imported ->
                    mergePhotoImports(imported)
                }.onFailure { error ->
                    pdfError = error.message ?: "Photos could not be opened"
                }
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            photoSourceMenu = false
            capturing = true
        } else {
            Toast.makeText(context, "Camera permission is needed to take photos", Toast.LENGTH_LONG).show()
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
                scope.launch { mergePhotoImports(listOf(imported)) }
                capturing = false
            },
            onBack = { capturing = false },
        )
        return
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
            title = if (field == ManualCardField.NAME) "name" else "data",
            initial = if (field == ManualCardField.NAME) name else data,
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
            val bitmap = Barcodes.generate(selected, trimmedData, 320)
            (bitmap != null).also { bitmap?.recycle() }
        }
        renderCheck = renderKey to verified
    }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("card", discardAndBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(items) { item ->
                when (item) {
                    ManualCardItem.Name -> ManualEntryRow("name", name, "e.g. Billa") {
                        editingField = ManualCardField.NAME
                    }
                    ManualCardItem.Data -> if (format == null) {
                        SettingsItem(
                            label = if (pdfChecking) "checking…" else if (photoMode) {
                                when (photoImports.size) {
                                    1 -> "add back photo"
                                    2 -> "change photos"
                                    else -> "choose photos"
                                }
                            } else "choose PDF",
                            trailing = if (photoMode) photoImports.takeIf { it.isNotEmpty() }?.let { "${it.size}/2" }
                            else pdfImport?.let { "${it.pageCount}p" },
                            onClick = {
                                if (!pdfChecking) {
                                    if (photoMode) {
                                        photoSourceMenu = true
                                    } else {
                                        context.setPakaExternalFlowActive(true)
                                        pdfPicker.launch(arrayOf("application/pdf"))
                                    }
                                }
                            },
                        )
                    } else {
                        ManualEntryRow("data", data, "code content") {
                            editingField = ManualCardField.DATA
                        }
                    }
                    is ManualCardItem.Format -> {
                        val f = item.format
                        Row(
                            modifier = Modifier.fillMaxWidth().then(tapModifier { format = f }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = f.label(),
                                color = if (f == format) White else Grey,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.weight(1f),
                            )
                            if (f == format) Text("selected", color = Grey, fontSize = 14.sp, fontWeight = FontWeight.Light)
                        }
                    }
                    ManualCardItem.PdfDocument -> Row(
                        modifier = Modifier.fillMaxWidth().then(tapModifier {
                            if (!documentImportsEnabled) {
                                // Imported blobs live in the real store; a demo card
                                // cannot reference one without orphaning it later.
                                Toast.makeText(context, "PDF passes are unavailable in demo mode", Toast.LENGTH_SHORT).show()
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
                                pdfError = "PDF passes require Android 11 or newer"
                            }
                        }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "PDF document",
                            color = if (format == null && !photoMode) White else Grey,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                        )
                        if (format == null && !photoMode) Text("selected", color = Grey, fontSize = 14.sp, fontWeight = FontWeight.Light)
                    }
                    ManualCardItem.PhotoDocument -> Row(
                        modifier = Modifier.fillMaxWidth().then(tapModifier {
                            if (!documentImportsEnabled) {
                                Toast.makeText(context, "Photo passes are unavailable in demo mode", Toast.LENGTH_SHORT).show()
                                return@tapModifier
                            }
                            format = null
                            photoMode = true
                            pdfError = null
                            if (!pdfChecking) photoSourceMenu = true
                        }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "photo pass",
                            color = if (format == null && photoMode) White else Grey,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                        )
                        if (format == null && photoMode) Text("selected", color = Grey, fontSize = 14.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
        val actionError = if (format == null) pdfError else validationError
        val actionText = actionError ?: if (validating || pdfChecking) "checking…" else "save"
        Text(
            text = actionText,
            color = if (canSave) White else Grey,
            fontSize = if (actionError == null) 18.sp else 14.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 18.dp).then(
                if (canSave) tapModifier {
                    val selected = format
                    if (selected == null) {
                        if (photoMode) {
                            pdfImport?.takeIf { it.created }?.let { orphan ->
                                scope.launch(Dispatchers.IO) { PdfStore.delete(context, orphan.documentId) }
                            }
                            onSave(Card(name = name.trim(), content = PassContent.Photos(photoImports.map(PhotoImport::page))))
                        } else {
                            photoImports.filter { it.created }.forEach { orphan ->
                                scope.launch(Dispatchers.IO) { PhotoStore.delete(context, orphan.page.documentId) }
                            }
                            val imported = checkNotNull(pdfImport)
                            onSave(Card(name = name.trim(), content = PassContent.Pdf(imported.documentId, imported.pageCount)))
                        }
                    } else {
                        pdfImport?.takeIf { it.created }?.let { orphan ->
                            scope.launch(Dispatchers.IO) { PdfStore.delete(context, orphan.documentId) }
                        }
                        photoImports.filter { it.created }.forEach { orphan ->
                            scope.launch(Dispatchers.IO) { PhotoStore.delete(context, orphan.page.documentId) }
                        }
                        onSave(Card(name = name.trim(), data = trimmedData, format = selected))
                    }
                }
                else Modifier,
            ),
        )
    }
}

@Composable
private fun PhotoSourceScreen(secondSide: Boolean, onTake: () -> Unit, onChoose: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(if (secondSide) "back side" else "photos", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(listOf("take photo", "choose photos")) { action ->
                Text(
                    action,
                    color = White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().then(
                        tapModifier { if (action == "take photo") onTake() else onChoose() },
                    ),
                )
            }
            Text(
                "Taken photos go straight into Paka's encrypted store. " +
                    "They are never written to the gallery or any file.",
                color = Grey,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.BottomStart).padding(end = 14.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
internal fun ManualCodeScreen(onSave: (OtpAccount) -> Unit, onBack: () -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<ManualCodeItem?>(null) }
    val draft = OtpAccount(
        issuer = issuer.trim(),
        account = account.trim(),
        secret = secret.replace(" ", "").trim(),
    )
    val validationError = if (issuer.isBlank() || secret.isBlank()) null else Totp.validationError(draft)
    val canSave = issuer.isNotBlank() && secret.isNotBlank() && validationError == null

    editingItem?.let { item ->
        TextEntryScreen(
            title = when (item) {
                ManualCodeItem.NAME -> "name"
                ManualCodeItem.ACCOUNT -> "account"
                ManualCodeItem.SECRET -> "secret"
            },
            initial = when (item) {
                ManualCodeItem.NAME -> issuer
                ManualCodeItem.ACCOUNT -> account
                ManualCodeItem.SECRET -> secret
            },
            visualTransformation = if (item == ManualCodeItem.SECRET) PasswordVisualTransformation() else VisualTransformation.None,
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

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("code", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(ManualCodeItem.entries) { item ->
                when (item) {
                    ManualCodeItem.NAME -> ManualEntryRow("name", issuer, "e.g. GitHub") { editingItem = item }
                    ManualCodeItem.ACCOUNT -> ManualEntryRow("account", account, "optional") { editingItem = item }
                    ManualCodeItem.SECRET -> ManualEntryRow(
                        "secret",
                        if (secret.isEmpty()) "" else "•".repeat(min(secret.length, 16)),
                        "base32 key",
                    ) { editingItem = item }
                }
            }
        }
        val actionText = validationError ?: "save"
        Text(
            text = actionText,
            color = if (canSave) White else Grey,
            fontSize = if (validationError == null) 18.sp else 14.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 18.dp).then(if (canSave) tapModifier { onSave(draft) } else Modifier),
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
) {
    var text by remember { mutableStateOf(initial) }
    val canSave = allowBlank || text.isNotBlank()
    val savedText = { if (trimOnSave) text.trim() else text }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { onBack() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(title, onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    textStyle = TextStyle(color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal),
                    cursorBrush = SolidColor(White),
                    keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) { keyboard?.hide(); onSave(savedText()) } }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (singleLine) Modifier else Modifier.height(180.dp))
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(White))
            }
        }
        Text(
            text = "save",
            color = if (canSave) White else Grey,
            fontSize = 18.sp,
            modifier = Modifier.padding(vertical = 18.dp).then(
                if (canSave) tapModifier {
                    keyboard?.hide()
                    onSave(savedText())
                } else Modifier,
            ),
        )
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, color = Grey, fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 2.sp)
}

@Composable
internal fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(value, color = White, fontSize = 20.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
internal fun ManualEntryRow(label: String, value: String, placeholder: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().then(tapModifier(onClick))) {
        FieldLabel(label)
        Spacer(Modifier.height(6.dp))
        Text(
            text = value.ifEmpty { placeholder },
            color = if (value.isEmpty()) Grey else White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Grey))
    }
}

@Composable
internal fun ReferenceEntryRow(label: String, reference: PassReference?, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().then(tapModifier(onClick))) {
        FieldLabel(label)
        Spacer(Modifier.height(6.dp))
        Text(
            text = reference?.name ?: "add a file",
            color = if (reference == null) Grey else White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (reference != null) {
            Spacer(Modifier.height(2.dp))
            Text("external · not encrypted", color = Grey, fontSize = 12.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Grey))
    }
}
