package de.codevoid.androsnd

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

class MetadataCache(context: Context) {

    companion object {
        private const val TAG = "MetadataCache"
        private const val CACHE_FILE = "metadata_cache.json"
    }

    data class Entry(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val lastModified: Long
    )

    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val entries = HashMap<String, Entry>()

    init {
        load()
    }

    @Synchronized
    fun get(uriString: String, lastModified: Long): Entry? {
        if (lastModified <= 0L) return null
        val entry = entries[uriString] ?: return null
        return if (entry.lastModified == lastModified) entry else null
    }

    @Synchronized
    fun put(uriString: String, entry: Entry) {
        entries[uriString] = entry
    }

    @Synchronized
    fun cleanup(usedKeys: Set<String>) {
        val before = entries.size
        entries.keys.retainAll(usedKeys)
        val removed = before - entries.size
        if (removed > 0) Log.d(TAG, "Removed $removed stale cache entries")
        save()
    }

    private fun load() {
        if (!cacheFile.exists()) return
        try {
            val json = JSONObject(cacheFile.readText())
            val obj = json.getJSONObject("entries")
            for (key in obj.keys()) {
                val e = obj.getJSONObject(key)
                entries[key] = Entry(
                    title = e.getString("title"),
                    artist = e.getString("artist"),
                    album = e.getString("album"),
                    duration = e.getLong("duration"),
                    lastModified = e.getLong("lastModified")
                )
            }
            Log.d(TAG, "Loaded ${entries.size} cache entries")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load metadata cache, starting fresh", e)
            entries.clear()
        }
    }

    private fun save() {
        try {
            val obj = JSONObject()
            for ((key, entry) in entries) {
                obj.put(key, JSONObject().apply {
                    put("title", entry.title)
                    put("artist", entry.artist)
                    put("album", entry.album)
                    put("duration", entry.duration)
                    put("lastModified", entry.lastModified)
                })
            }
            cacheFile.writeText(JSONObject().apply { put("entries", obj) }.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save metadata cache", e)
        }
    }
}
