package com.nuvio.tv.core.calendar

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * The Calendar's files, per profile, in the app's private storage: what the schedule sources said about each
 * followed show (a cache, refreshed after 12 h) and the reminders. Plain org.json, so R8 needs no keep rules.
 */
internal class CalendarStore(context: Context) {
    private val dir = File(context.filesDir, "calendar")

    private fun showsFile(profileId: Int) = File(dir, "shows-p$profileId.json")
    private fun remindersFile(profileId: Int) = File(dir, "reminders-p$profileId.json")

    fun readShows(profileId: Int): Map<String, CalendarShow> {
        val array = read(showsFile(profileId)) ?: return emptyMap()
        val out = LinkedHashMap<String, CalendarShow>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val show = runCatching { o.toShow() }.getOrNull() ?: continue
            out[show.id] = show
        }
        return out
    }

    fun writeShows(profileId: Int, shows: Collection<CalendarShow>) {
        write(showsFile(profileId), JSONArray().apply { shows.forEach { put(it.toJson()) } })
    }

    fun readReminders(profileId: Int): Map<String, CalendarReminder> {
        val array = read(remindersFile(profileId)) ?: return emptyMap()
        val out = LinkedHashMap<String, CalendarReminder>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val showId = o.optString("showId").takeIf { it.isNotBlank() } ?: continue
            val since = date(o.optString("since")) ?: continue
            out[showId] = CalendarReminder(
                showId = showId,
                title = o.optString("title"),
                since = since,
                notifiedThrough = date(o.optString("notifiedThrough"))
            )
        }
        return out
    }

    fun writeReminders(profileId: Int, reminders: Collection<CalendarReminder>) {
        write(remindersFile(profileId), JSONArray().apply {
            reminders.forEach { r ->
                put(JSONObject().apply {
                    put("showId", r.showId)
                    put("title", r.title)
                    put("since", r.since.toString())
                    r.notifiedThrough?.let { put("notifiedThrough", it.toString()) }
                })
            }
        })
    }

    private fun read(file: File): JSONArray? {
        if (!file.isFile) return null
        return runCatching { JSONArray(file.readText()) }
            .onFailure { Log.w(TAG, "unreadable ${file.name}: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Written beside the target and renamed over it, so a crash mid-write never leaves half a file. */
    private fun write(file: File, array: JSONArray) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "couldn't write ${file.name}: ${it.javaClass.simpleName}") }
    }

    private fun CalendarShow.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        poster?.let { put("poster", it) }
        backdrop?.let { put("backdrop", it) }
        put("status", status.name)
        next?.let { put("next", it.toJson()) }
        last?.let { put("last", it.toJson()) }
        network?.let { put("network", it) }
        seasons?.let { put("seasons", it) }
        genre?.let { put("genre", it) }
        addonBaseUrl?.let { put("addonBaseUrl", it) }
        tmdbId?.let { put("tmdbId", it) }
        put("fetchedAt", fetchedAt)
    }

    private fun JSONObject.toShow() = CalendarShow(
        id = getString("id"),
        title = optString("title"),
        poster = str("poster"),
        backdrop = str("backdrop"),
        status = runCatching { CalendarStatus.valueOf(optString("status")) }.getOrDefault(CalendarStatus.UNKNOWN),
        next = optJSONObject("next")?.toEpisode(),
        last = optJSONObject("last")?.toEpisode(),
        network = str("network"),
        seasons = if (has("seasons")) optInt("seasons") else null,
        genre = str("genre"),
        addonBaseUrl = str("addonBaseUrl"),
        tmdbId = if (has("tmdbId")) optInt("tmdbId") else null,
        fetchedAt = optLong("fetchedAt")
    )

    private fun CalendarEpisode.toJson() = JSONObject().apply {
        put("season", season)
        put("episode", episode)
        name?.let { put("name", it) }
        airDate?.let { put("airDate", it.toString()) }
        overview?.let { put("overview", it) }
        runtimeMinutes?.let { put("runtime", it) }
        still?.let { put("still", it) }
        type?.let { put("type", it) }
    }

    private fun JSONObject.toEpisode() = CalendarEpisode(
        season = optInt("season"),
        episode = optInt("episode"),
        name = str("name"),
        airDate = date(optString("airDate")),
        overview = str("overview"),
        runtimeMinutes = if (has("runtime")) optInt("runtime") else null,
        still = str("still"),
        type = str("type")
    )

    private fun JSONObject.str(name: String): String? = if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun date(raw: String?): LocalDate? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private companion object {
        const val TAG = "Calendar"
    }
}
