package com.paka.app

import java.util.UUID

sealed interface PassContent {
    data class Barcode(val format: PakaFormat, val data: String) : PassContent
    data class Pdf(val documentId: String, val pageCount: Int) : PassContent
}

/** A persistable Android document link. The referenced file is not copied or encrypted by Paka. */
data class PassReference(
    val uri: String,
    val name: String,
    val mimeType: String,
)

data class Card(
    val name: String,
    val content: PassContent,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    val stack: String? = null,
    val references: List<PassReference> = emptyList(),
) {
    constructor(
        name: String,
        data: String,
        format: PakaFormat,
        id: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis(),
        notes: String = "",
        stack: String? = null,
        references: List<PassReference> = emptyList(),
    ) : this(name, PassContent.Barcode(format, data), id, createdAt, notes, stack, references)
}

internal val Card.barcodeContent: PassContent.Barcode?
    get() = content as? PassContent.Barcode

internal val Card.pdfContent: PassContent.Pdf?
    get() = content as? PassContent.Pdf
