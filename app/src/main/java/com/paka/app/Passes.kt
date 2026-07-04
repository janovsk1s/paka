package com.paka.app

import java.util.UUID

sealed interface PassContent {
    data class Barcode(val format: PakaFormat, val data: String) : PassContent
    data class Pdf(val documentId: String, val pageCount: Int) : PassContent
    data class Photos(val pages: List<PhotoPage>) : PassContent {
        init {
            require(pages.size in 1..2) { "A photo pass must contain one or two images" }
        }
    }
}

data class PhotoPage(
    val documentId: String,
    val width: Int,
    val height: Int,
)

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

internal val Card.photoContent: PassContent.Photos?
    get() = content as? PassContent.Photos

internal fun Iterable<Card>.photoDocumentIds(): Set<String> =
    flatMap { it.photoContent?.pages.orEmpty() }.mapTo(linkedSetOf(), PhotoPage::documentId)

/**
 * Swaps a stacked pass with its neighbour inside the same stack. Only the two
 * members trade places in the overall list, so singles and other stacks keep
 * their positions, and the stack itself stays anchored where it first appears.
 */
internal fun List<Card>.movedWithinStack(id: String, up: Boolean): List<Card> {
    val index = indexOfFirst { it.id == id }
    val stackName = getOrNull(index)?.stack
    val candidates: IntProgression = when {
        stackName == null -> IntRange.EMPTY
        up -> index - 1 downTo 0
        else -> index + 1 until size
    }
    val neighbor = candidates.firstOrNull { this[it].stack == stackName }
    return if (neighbor == null) {
        this
    } else {
        val reordered = toMutableList()
        reordered[index] = this[neighbor]
        reordered[neighbor] = this[index]
        reordered
    }
}
