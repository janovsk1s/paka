package com.paka.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DetailField { NAME, STACK, NOTES }

private fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

private fun Context.passReference(uri: Uri): PassReference {
    val displayName = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "reference" }
    return PassReference(
        uri = uri.toString(),
        name = displayName.take(512),
        mimeType = (contentResolver.getType(uri) ?: "application/octet-stream").take(256),
    )
}

@Composable
private fun ConfirmDeleteScreen(name: String, onConfirm: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("delete $name?", color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(44.dp))
        Text("delete", color = White, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onConfirm)))
        Spacer(Modifier.height(28.dp))
        Text("cancel", color = Grey, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onBack)))
    }
}

@Composable
internal fun DuplicateConfirmScreen(kind: String, existingName: String, onConfirm: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$kind already added", color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(18.dp))
        Text("matches $existingName", color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(44.dp))
        Text("add anyway", color = White, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onConfirm)))
        Spacer(Modifier.height(28.dp))
        Text("cancel", color = Grey, fontSize = 24.sp, modifier = Modifier.fillMaxWidth().then(tapModifier(onBack)))
    }
}

@Composable
private fun ReferenceOptionsScreen(
    name: String,
    onOpen: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("reference", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(listOf("open", "replace", "remove")) { action ->
                Text(
                    action,
                    color = White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().then(
                        tapModifier {
                            when (action) {
                                "open" -> onOpen()
                                "replace" -> onReplace()
                                else -> onRemove()
                            }
                        },
                    ),
                )
            }
            Text(
                "$name is an external file. Its contents are not encrypted or backed up by Paka.",
                color = Grey,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(end = 14.dp, bottom = 18.dp),
            )
        }
    }
}

@Composable
internal fun CardDetail(card: Card, onUpdate: (Card) -> Boolean, onDelete: () -> Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var name by remember(card.id) { mutableStateOf(card.name) }
    var notes by remember(card.id) { mutableStateOf(card.notes) }
    var stack by remember(card.id) { mutableStateOf(card.stack ?: "") }
    var references by remember(card.id) { mutableStateOf(card.references.take(2)) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }
    var editingField by remember(card.id) { mutableStateOf<DetailField?>(null) }
    var activeReferenceIndex by remember(card.id) { mutableStateOf<Int?>(null) }
    val addReferencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        context.setPakaExternalFlowActive(false)
        val remaining = (2 - references.size).coerceAtLeast(0)
        val additions = uris
            .distinctBy(Uri::toString)
            .filterNot { candidate -> references.any { it.uri == candidate.toString() } }
            .take(remaining)
            .mapNotNull { uri ->
                val retained = runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
                if (retained) context.passReference(uri) else null
            }
        if (additions.isNotEmpty()) references = references + additions
        if (uris.isNotEmpty() && additions.isEmpty() && remaining > 0) {
            Toast.makeText(context, "Paka could not keep access to those files", Toast.LENGTH_LONG).show()
        }
    }
    val replaceReferencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        context.setPakaExternalFlowActive(false)
        val index = activeReferenceIndex
        if (uri != null && index != null && index in references.indices) {
            val retained = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (retained) {
                references = references.toMutableList().also { it[index] = context.passReference(uri) }
                activeReferenceIndex = null
            } else {
                Toast.makeText(context, "Paka could not keep access to that file", Toast.LENGTH_LONG).show()
            }
        }
    }
    val referenceViewer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        context.setPakaExternalFlowActive(false)
    }
    val chooseReferences: () -> Unit = {
        context.setPakaExternalFlowActive(true)
        runCatching { addReferencePicker.launch(arrayOf("*/*")) }.onFailure {
            context.setPakaExternalFlowActive(false)
            Toast.makeText(context, "File picker could not be opened", Toast.LENGTH_LONG).show()
        }
    }
    val replaceReference: () -> Unit = {
        context.setPakaExternalFlowActive(true)
        runCatching { replaceReferencePicker.launch(arrayOf("*/*")) }.onFailure {
            context.setPakaExternalFlowActive(false)
            Toast.makeText(context, "File picker could not be opened", Toast.LENGTH_LONG).show()
        }
    }
    val openReference: (Int) -> Unit = { index ->
        references.getOrNull(index)?.let { selected ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(selected.uri), selected.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.setPakaExternalFlowActive(true)
            runCatching { referenceViewer.launch(intent) }.onFailure {
                context.setPakaExternalFlowActive(false)
                Toast.makeText(context, "No app can open this file", Toast.LENGTH_LONG).show()
            }
        }
    }
    val persistAndBack = {
        val savedName = name.trim().ifBlank { card.name }
        if (onUpdate(card.copy(name = savedName, notes = notes.trim(), stack = stack.trim().ifBlank { null }, references = references))) onBack()
    }

    if (confirmDelete) {
        ConfirmDeleteScreen(
            name = name.trim().ifBlank { card.name },
            onConfirm = { if (onDelete()) onBack() },
            onBack = { confirmDelete = false },
        )
        return
    }

    editingField?.let { field ->
        TextEntryScreen(
            title = when (field) {
                DetailField.NAME -> "name"
                DetailField.STACK -> "stack"
                DetailField.NOTES -> "notes"
            },
            initial = when (field) {
                DetailField.NAME -> name
                DetailField.STACK -> stack
                DetailField.NOTES -> notes
            },
            allowBlank = field != DetailField.NAME,
            singleLine = field != DetailField.NOTES,
            onSave = { value ->
                when (field) {
                    DetailField.NAME -> name = value
                    DetailField.STACK -> stack = value
                    DetailField.NOTES -> notes = value
                }
                editingField = null
            },
            onBack = { editingField = null },
        )
        return
    }

    val selectedReferenceIndex = activeReferenceIndex
    if (selectedReferenceIndex != null && selectedReferenceIndex in references.indices) {
        ReferenceOptionsScreen(
            name = references[selectedReferenceIndex].name,
            onOpen = { openReference(selectedReferenceIndex) },
            onReplace = replaceReference,
            onRemove = {
                references = references.filterIndexed { index, _ -> index != selectedReferenceIndex }
                activeReferenceIndex = null
            },
            onBack = { activeReferenceIndex = null },
        )
        return
    }
    BackHandler { persistAndBack() }

    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("details", persistAndBack)
        HardCutPager(pageCount = 3, modifier = Modifier.weight(1f).fillMaxWidth()) { page, _ ->
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (page == 0) {
                    ManualEntryRow("name", name, card.name) { editingField = DetailField.NAME }
                    ManualEntryRow("stack", stack, "none") { editingField = DetailField.STACK }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LabelValue(
                            "format",
                            when (val content = card.content) {
                                is PassContent.Barcode -> content.format.label()
                                is PassContent.Pdf -> "PDF · ${content.pageCount} page${if (content.pageCount == 1) "" else "s"}"
                                is PassContent.Photos -> "photos · ${content.pages.size} side${if (content.pages.size == 1) "" else "s"}"
                            },
                            Modifier.weight(1f),
                        )
                        LabelValue("added", formatDate(card.createdAt), Modifier.weight(1f))
                    }
                } else if (page == 1) {
                    Column {
                        FieldLabel(
                            when (card.content) {
                                is PassContent.Barcode -> "code"
                                is PassContent.Pdf -> "document"
                                is PassContent.Photos -> "photos"
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (val content = card.content) {
                                is PassContent.Barcode -> content.data
                                is PassContent.Pdf -> "encrypted document · ${content.documentId.take(12)}"
                                is PassContent.Photos -> "encrypted photos · ${content.pages.joinToString(" · ") { it.documentId.take(8) }}"
                            },
                            color = Grey,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Grey.copy(alpha = 0.5f)))
                    ManualEntryRow("notes", notes, "add a note") { editingField = DetailField.NOTES }
                } else {
                    ReferenceEntryRow("reference 1", references.getOrNull(0)) {
                        if (references.isEmpty()) chooseReferences() else activeReferenceIndex = 0
                    }
                    ReferenceEntryRow("reference 2", references.getOrNull(1)) {
                        if (references.size < 2) chooseReferences() else activeReferenceIndex = 1
                    }
                    Text(
                        "Select up to two files at once. References stay external and are not encrypted or backed up by Paka.",
                        color = Grey,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "save",
                color = if (name.isBlank()) Grey else White,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f).then(if (name.isNotBlank()) tapModifier(persistAndBack) else Modifier),
            )
            Text(
                text = "delete",
                color = White,
                fontSize = 18.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).then(tapModifier { confirmDelete = true }),
            )
        }
    }
}
