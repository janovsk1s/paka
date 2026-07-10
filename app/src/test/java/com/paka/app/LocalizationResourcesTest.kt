package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {
    @Test
    fun everyLocaleHasEveryTranslatableResourceWithMatchingPlaceholders() {
        val root = resourceRoot()
        val base = readResources(File(root, "values"))
            .filterValues(ResourceEntry::translatable)

        AppLanguage.supportedTags.filterNot { it == "en" }.forEach { tag ->
            val localized = readResources(File(root, "values-$tag"))
            assertEquals("$tag resource keys", base.keys, localized.keys)
            base.forEach { (name, expected) ->
                val actual = checkNotNull(localized[name])
                assertEquals("$tag type for $name", expected.type, actual.type)
                assertEquals("$tag placeholders for $name", expected.placeholders, actual.placeholders)
            }
        }
    }

    @Test
    fun pluralsUseTheCompleteLanguageCategories() {
        val root = resourceRoot()
        val expected = mapOf(
            "de" to setOf("one", "other"),
            "et" to setOf("one", "other"),
            "fi" to setOf("one", "other"),
            "sv" to setOf("one", "other"),
            "lv" to setOf("zero", "one", "other"),
            "lt" to setOf("one", "few", "many", "other"),
            "sk" to setOf("one", "few", "many", "other"),
        )

        expected.forEach { (tag, quantities) ->
            readResources(File(root, "values-$tag")).forEach { (name, entry) ->
                if (entry.type == "plurals") {
                    assertEquals("$tag quantities for $name", quantities, entry.quantities)
                }
            }
        }
    }

    private fun resourceRoot(): File {
        val workingDirectory = File(System.getProperty("user.dir") ?: error("Working directory is unavailable"))
        return listOf(
            File(workingDirectory, "src/main/res"),
            File(workingDirectory, "app/src/main/res"),
        ).firstOrNull(File::isDirectory).also {
            assertTrue("Android resource directory was not found", it != null)
        } ?: error("Android resource directory was not found")
    }

    private fun readResources(directory: File): Map<String, ResourceEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return directory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("strings") && it.extension == "xml" }
            .sortedBy(File::getName)
            .flatMap { file -> readResourceFile(factory, file) }
            .toMap(linkedMapOf())
    }

    private fun readResourceFile(
        factory: DocumentBuilderFactory,
        file: File,
    ): List<Pair<String, ResourceEntry>> {
        val entries = mutableListOf<Pair<String, ResourceEntry>>()
        val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element
            if (element != null) {
                resourceEntry(element, file)?.let(entries::add)
            }
        }
        return entries
    }

    private fun resourceEntry(element: Element, file: File): Pair<String, ResourceEntry>? {
        if (element.tagName !in setOf("string", "plurals")) return null
        val name = element.getAttribute("name")
        val values = if (element.tagName == "plurals") {
            val items = element.getElementsByTagName("item")
            (0 until items.length).map { items.item(it) as Element }
        } else {
            listOf(element)
        }
        val signatures = values.map { value ->
            PLACEHOLDER.findAll(value.textContent).mapTo(linkedSetOf()) { it.value }
        }.toSet()
        check(signatures.size == 1) { "$file has inconsistent placeholders for $name" }
        return name to ResourceEntry(
            type = element.tagName,
            placeholders = signatures.single(),
            quantities = values.mapTo(linkedSetOf()) { it.getAttribute("quantity") }
                .filterTo(linkedSetOf()) { it.isNotEmpty() },
            translatable = element.getAttribute("translatable") != "false",
        )
    }

    private data class ResourceEntry(
        val type: String,
        val placeholders: Set<String>,
        val quantities: Set<String>,
        val translatable: Boolean,
    )

    private companion object {
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[a-zA-Z]")
    }
}
