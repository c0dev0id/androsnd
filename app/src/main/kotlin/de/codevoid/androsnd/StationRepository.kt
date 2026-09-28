package de.codevoid.androsnd

import android.content.Context
import android.net.Uri
import de.codevoid.androsnd.model.RadioStation
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.StationGroup
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Supplies the radio subsystem's library from two JSON sources: a bundled set of
 * curated groups ([R.raw.stations]) and a user-editable file in filesDir. Both use the
 * same schema and are merged by group name.
 *
 * The user file lives in filesDir, not in metadata.db, because that database is dropped
 * and recreated on any schema change (pre-1.0 policy) — user-authored stations must
 * survive such a reset.
 */
open class StationRepository(private val context: Context) {

    companion object {
        private const val USER_FILE = "user_stations.json"
        const val USER_GROUP = "My Streams"

        /**
         * Turns the JSON document into groups. Malformed or incomplete entries (missing
         * name/url) are skipped rather than aborting the whole parse, so one bad line in
         * a hand-edited file does not empty the library.
         */
        fun parse(json: String): List<StationGroup> {
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return emptyList()
            }
            val groupsArr = root.optJSONArray("groups") ?: return emptyList()
            val groups = mutableListOf<StationGroup>()
            for (i in 0 until groupsArr.length()) {
                val g = groupsArr.optJSONObject(i) ?: continue
                val groupName = g.optString("name").trim()
                if (groupName.isEmpty()) continue
                val stationsArr = g.optJSONArray("stations") ?: JSONArray()
                val stations = mutableListOf<RadioStation>()
                for (j in 0 until stationsArr.length()) {
                    val s = stationsArr.optJSONObject(j) ?: continue
                    val name = s.optString("name").trim()
                    val url = s.optString("url").trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    val logo = s.optString("logo").trim().ifEmpty { null }
                    stations.add(RadioStation(name, url, logo))
                }
                groups.add(StationGroup(groupName, stations))
            }
            return groups
        }

        fun serialize(groups: List<StationGroup>): String {
            val arr = JSONArray()
            for (g in groups) {
                val stationsArr = JSONArray()
                for (s in g.stations) {
                    val so = JSONObject()
                    so.put("name", s.name)
                    so.put("url", s.url)
                    if (s.logo != null) so.put("logo", s.logo)
                    stationsArr.put(so)
                }
                arr.put(JSONObject().put("name", g.name).put("stations", stationsArr))
            }
            return JSONObject().put("groups", arr).toString(2)
        }

        /**
         * Merges by group name, bundled first, so a user group named like a bundled one
         * appends to it rather than replacing it.
         */
        fun merge(bundled: List<StationGroup>, user: List<StationGroup>): List<StationGroup> {
            if (user.isEmpty()) return bundled
            val byName = LinkedHashMap<String, MutableList<RadioStation>>()
            for (g in bundled + user) byName.getOrPut(g.name) { mutableListOf() }.addAll(g.stations)
            return byName.map { StationGroup(it.key, it.value) }
        }
    }

    open fun load(): List<StationGroup> = merge(loadBundled(), loadUserGroups())

    /** Groups shaped as the same input [Library.of] takes for the file library. */
    fun toScannedFolders(groups: List<StationGroup>): List<ScannedFolder> =
        groups.map { group ->
            val path = "/radio/${group.name}"
            ScannedFolder(
                name = group.name,
                path = path,
                coverUri = null,
                songs = group.stations.map { station ->
                    Song(
                        uri = Uri.parse(station.url),
                        displayName = station.name,
                        folderPath = path,
                        folderName = group.name
                    )
                }
            )
        }

    fun addUserStation(name: String, url: String) {
        val groups = loadUserGroups().toMutableList()
        val idx = groups.indexOfFirst { it.name == USER_GROUP }
        val station = RadioStation(name.trim(), url.trim())
        if (idx >= 0) {
            groups[idx] = groups[idx].copy(stations = groups[idx].stations + station)
        } else {
            groups.add(StationGroup(USER_GROUP, listOf(station)))
        }
        userFile().writeText(serialize(groups))
    }

    private fun userFile() = File(context.filesDir, USER_FILE)

    private fun loadUserGroups(): List<StationGroup> {
        val file = userFile()
        return if (file.exists()) parse(file.readText()) else emptyList()
    }

    private fun loadBundled(): List<StationGroup> =
        try {
            context.resources.openRawResource(R.raw.stations)
                .bufferedReader().use { it.readText() }
                .let { parse(it) }
        } catch (e: Exception) {
            emptyList()
        }
}
