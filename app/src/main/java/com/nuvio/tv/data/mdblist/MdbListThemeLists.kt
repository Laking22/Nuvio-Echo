package com.nuvio.tv.data.mdblist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A list as Smart Highlights sees it: from `/lists/user` (the account's own, static and dynamic) or `/lists/top`.
 * [ids] holds both halves of a movie + show twin when the response was unified.
 */
data class MdbListThemeList(
    val ids: List<Long>,
    val name: String,
    val description: String?,
    val itemCount: Int?,
    val likes: Int?,
    val private: Boolean
)

/** The lists in a `/lists/user` or `/lists/top` body; rows without an id or a name are skipped, not fatal. */
internal fun decodeMdbListThemeLists(body: String): List<MdbListThemeList> {
    val rows = mdbListResponseElement(body) as? JsonArray ?: throw MdbListDecodingException()
    return rows.mapNotNull { element ->
        val row = runCatching { element.objectValue() }.getOrNull() ?: return@mapNotNull null
        val ids = (row.number("id")?.let(::listOf) ?: row.arrayValue("ids").mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        }).filter { it > 0 }
        val name = row.text("name") ?: return@mapNotNull null
        if (ids.isEmpty()) return@mapNotNull null
        MdbListThemeList(
            ids = ids,
            name = name,
            description = row.text("description"),
            itemCount = row.integer("items"),
            likes = row.integer("likes"),
            private = row.flag("private") == true
        )
    }.distinctBy { it.ids }
}
