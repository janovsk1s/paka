package com.paka.app

import java.util.UUID

sealed interface PassContent {
    data class Barcode(val format: PakaFormat, val data: String) : PassContent
    data class Pdf(val documentId: String, val pageCount: Int) : PassContent
}

data class Card(
    val name: String,
    val content: PassContent,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    val stack: String? = null,
) {
    constructor(
        name: String,
        data: String,
        format: PakaFormat,
        id: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis(),
        notes: String = "",
        stack: String? = null,
    ) : this(name, PassContent.Barcode(format, data), id, createdAt, notes, stack)
}

internal val Card.barcodeContent: PassContent.Barcode?
    get() = content as? PassContent.Barcode

internal val Card.pdfContent: PassContent.Pdf?
    get() = content as? PassContent.Pdf
