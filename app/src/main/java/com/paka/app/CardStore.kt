package com.paka.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Simple on-device JSON persistence for cards. Nothing leaves the phone. */
object CardStore {
    private const val FILE = "cards.json"

    fun load(context: Context): List<Card> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val format = runCatching { PakaFormat.valueOf(o.getString("format")) }.getOrNull()
                    ?: return@mapNotNull null
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, cards: List<Card>) {
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
        runCatching { File(context.filesDir, FILE).writeText(arr.toString()) }
    }
}
