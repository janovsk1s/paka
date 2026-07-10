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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date

private enum class DetailField { NAME, STACK, NOTES }
private enum class ReferenceAction { OPEN, REPLACE, REMOVE }
internal enum class DuplicateKind { PASS, CODE }

@Composable
private fun ConfirmationAction(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (enabled) tapModifier(onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 24.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Context.formatDate(ms: Long): String =
    SimpleDateFormat(getString(R.string.detail_date_pattern), resources.configuration.locales[0]).format(Date(ms))

private fun Context.passReference(uri: Uri): PassReference {
    val displayName = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank {
        uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.detail_reference_fallback)
    }
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
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding()
            .padding(horizontal = 28.dp, vertical = 10.dp),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Text(
                stringResource(R.string.detail_delete_question, name),
                color = White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ConfirmationAction(stringResource(R.string.detail_delete_action), White, onClick = onConfirm)
        ConfirmationAction(stringResource(R.string.detail_cancel_action), Grey, onClick = onBack)
    }
}

@Composable
internal fun DuplicateConfirmScreen(
    kind: DuplicateKind,
    existingName: String,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding()
            .padding(horizontal = 28.dp, vertical = 10.dp),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    stringResource(
                        when (kind) {
                            DuplicateKind.PASS -> R.string.detail_duplicate_pass_title
                            DuplicateKind.CODE -> R.string.detail_duplicate_code_title
                        },
                    ),
                    color = White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.detail_duplicate_match, existingName),
                    color = Grey,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ConfirmationAction(stringResource(R.string.detail_add_anyway), White, onClick = onConfirm)
        ConfirmationAction(stringResource(R.string.detail_discard_add_action), Grey, onClick = onBack)
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
        SimpleTopBar(stringResource(R.string.detail_reference_title), onBack)
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PagedList(listOf(ReferenceAction.OPEN, ReferenceAction.REPLACE, ReferenceAction.REMOVE)) { action ->
                    val label = when (action) {
                        ReferenceAction.OPEN -> stringResource(R.string.detail_reference_open)
                        ReferenceAction.REPLACE -> stringResource(R.string.detail_reference_replace)
                        ReferenceAction.REMOVE -> stringResource(R.string.detail_reference_remove)
                    }
                    Box(
                        modifier = Modifier.fillMaxSize().then(
                            tapModifier {
                                when (action) {
                                    ReferenceAction.OPEN -> onOpen()
                                    ReferenceAction.REPLACE -> onReplace()
                                    ReferenceAction.REMOVE -> onRemove()
                                }
                            },
                        ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            label,
                            color = White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(end = 14.dp, bottom = 18.dp)) {
                Text(
                    stringResource(R.string.detail_external_reference_file, name),
                    color = White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.detail_external_reference_warning),
                    color = Grey,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                )
            }
        }
    }
}

@Composable
internal fun CardDetail(
    card: Card,
    onUpdate: (Card) -> Boolean,
    onDelete: () -> Boolean,
    stackMembers: (String) -> List<ManageRow>,
    onStackMove: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var name by remember(card.id) { mutableStateOf(card.name) }
    var notes by remember(card.id) { mutableStateOf(card.notes) }
    var stack by remember(card.id) { mutableStateOf(card.stack ?: "") }
    var references by remember(card.id) { mutableStateOf(card.references.take(2)) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }
    var editingField by remember(card.id) { mutableStateOf<DetailField?>(null) }
    var activeReferenceIndex by remember(card.id) { mutableStateOf<Int?>(null) }
    var managingStack by remember(card.id) { mutableStateOf(false) }
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
            Toast.makeText(
                context,
                resources.getString(R.string.detail_reference_access_many_failed),
                Toast.LENGTH_LONG,
            ).show()
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
                Toast.makeText(
                    context,
                    resources.getString(R.string.detail_reference_access_one_failed),
                    Toast.LENGTH_LONG,
                ).show()
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
            Toast.makeText(context, resources.getString(R.string.detail_file_picker_failed), Toast.LENGTH_LONG).show()
        }
    }
    val replaceReference: () -> Unit = {
        context.setPakaExternalFlowActive(true)
        runCatching { replaceReferencePicker.launch(arrayOf("*/*")) }.onFailure {
            context.setPakaExternalFlowActive(false)
            Toast.makeText(context, resources.getString(R.string.detail_file_picker_failed), Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, resources.getString(R.string.detail_file_open_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
    val persistAndBack = {
        val savedName = name.trim().ifBlank { card.name }
        if (onUpdate(card.copy(name = savedName, notes = notes.trim(), stack = stack.trim().ifBlank { null }, references = references))) onBack()
    }

    val savedStack = card.stack
    if (managingStack && savedStack != null) {
        ManageScreen(
            rows = stackMembers(savedStack),
            onUp = { onStackMove(it, true) },
            onDown = { onStackMove(it, false) },
            onBack = { managingStack = false },
        )
        return
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
                DetailField.NAME -> stringResource(R.string.detail_name_field)
                DetailField.STACK -> stringResource(R.string.detail_stack_field)
                DetailField.NOTES -> stringResource(R.string.detail_notes_field)
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
        SimpleTopBar(stringResource(R.string.detail_title), persistAndBack)
        HardCutPager(pageCount = 3, modifier = Modifier.weight(1f).fillMaxWidth()) { page, _ ->
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (page == 0) {
                    ManualEntryRow(stringResource(R.string.detail_name_field), name, card.name) {
                        editingField = DetailField.NAME
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            ManualEntryRow(
                                stringResource(R.string.detail_stack_field),
                                stack,
                                stringResource(R.string.detail_none),
                            ) { editingField = DetailField.STACK }
                        }
                        if (savedStack != null && stackMembers(savedStack).size >= 2) {
                            Text(
                                stringResource(R.string.detail_sort_action),
                                color = Grey,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .then(
                                        tapModifier(
                                            { managingStack = true },
                                            stringResource(R.string.detail_sort_stack_description),
                                        ),
                                    ),
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LabelValue(
                            stringResource(R.string.detail_format_label),
                            when (val content = card.content) {
                                is PassContent.Barcode -> content.format.label()
                                is PassContent.Pdf -> pluralStringResource(
                                    R.plurals.detail_pdf_page_count,
                                    content.pageCount,
                                    content.pageCount,
                                )
                                is PassContent.Photos -> pluralStringResource(
                                    R.plurals.detail_photo_side_count,
                                    content.pages.size,
                                    content.pages.size,
                                )
                            },
                            Modifier.weight(1f),
                        )
                        LabelValue(
                            stringResource(R.string.detail_added_label),
                            context.formatDate(card.createdAt),
                            Modifier.weight(1f),
                        )
                    }
                } else if (page == 1) {
                    Column {
                        FieldLabel(
                            when (card.content) {
                                is PassContent.Barcode -> stringResource(R.string.detail_code_label)
                                is PassContent.Pdf -> stringResource(R.string.detail_document_label)
                                is PassContent.Photos -> stringResource(R.string.detail_photos_label)
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (val content = card.content) {
                                is PassContent.Barcode -> content.data
                                is PassContent.Pdf -> stringResource(
                                    R.string.detail_encrypted_document,
                                    content.documentId.take(12),
                                )
                                is PassContent.Photos -> stringResource(
                                    R.string.detail_encrypted_photos,
                                    content.pages.joinToString(" · ") { it.documentId.take(8) },
                                )
                            },
                            color = Grey,
                            fontSize = 13.sp,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Grey.copy(alpha = 0.5f)))
                    ManualEntryRow(
                        stringResource(R.string.detail_notes_field),
                        notes,
                        stringResource(R.string.detail_add_note),
                    ) { editingField = DetailField.NOTES }
                } else {
                    ReferenceEntryRow(stringResource(R.string.detail_reference_one), references.getOrNull(0)) {
                        if (references.isEmpty()) chooseReferences() else activeReferenceIndex = 0
                    }
                    ReferenceEntryRow(stringResource(R.string.detail_reference_two), references.getOrNull(1)) {
                        if (references.size < 2) chooseReferences() else activeReferenceIndex = 1
                    }
                    Text(
                        stringResource(R.string.detail_references_description),
                        color = Grey,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(if (name.isNotBlank()) tapModifier(persistAndBack) else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.detail_save_action),
                    color = if (name.isBlank()) Grey else White,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier.weight(1f).height(48.dp).then(tapModifier { confirmDelete = true }),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = stringResource(R.string.detail_delete_action),
                    color = White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
