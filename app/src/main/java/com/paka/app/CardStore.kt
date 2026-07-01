package com.paka.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Simple on-device JSON persistence for cards. Nothing leaves the phone. */
internal object CardStore {
    private const val FILE = "cards.json"
    private const val SCHEMA = 1

    fun load(context: Context): LoadOutcome<List<Card>> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return LoadOutcome(emptyList())

        runCatching { return LoadOutcome(parse(AtomicStore.readBytes(file).toString(Charsets.UTF_8))) }

        val backup = AtomicStore.backupFile(file)
        if (backup.exists()) {
            runCatching {
                return LoadOutcome(
                    value = parse(AtomicStore.readBytes(backup).toString(Charsets.UTF_8)),
                    warning = "Cards were recovered from the previous backup.",
                )
            }
        }
        return LoadOutcome(
            value = emptyList(),
            warning = "Cards could not be read. The original file was preserved.",
            writable = false,
        )
    }

    private fun parse(json: String): List<Card> {
        val root = json.trim()
        val arr = if (root.startsWith("[")) {
            JSONArray(root) // v0 compatibility
        } else {
            val envelope = JSONObject(root)
            require(envelope.getInt("schema") == SCHEMA) { "Unsupported card-store version" }
            envelope.getJSONArray("cards")
        }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val format = PakaFormat.valueOf(o.getString("format"))
            Card(
                name = o.getString("name"),
                data = o.getString("data"),
                format = format,
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                notes = o.optString("notes", ""),
                stack = if (o.isNull("stack")) null else o.optString("stack", "").ifBlank { null },
            )
        }
    }

    fun save(context: Context, cards: List<Card>): Result<Unit> {
        val arr = JSONArray()
        for (c in cards) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("data", c.data)
                    .put("format", c.format.name)
                    .put("createdAt", c.createdAt)
                    .put("notes", c.notes)
                    .put("stack", c.stack ?: JSONObject.NULL),
            )
        }
        val envelope = JSONObject().put("schema", SCHEMA).put("cards", arr)
        return AtomicStore.write(
            File(context.filesDir, FILE),
            envelope.toString().toByteArray(Charsets.UTF_8),
        )
    }
}
