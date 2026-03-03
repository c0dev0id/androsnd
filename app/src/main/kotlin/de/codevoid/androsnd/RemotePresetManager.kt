package de.codevoid.androsnd

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Manages storage and retrieval of remote key presets.
 *
 * "DMD Remote 2" is the built-in default: always at index 0, never stored in JSON,
 * never deletable. Custom presets are persisted as a JSON array in SharedPreferences.
 */
class RemotePresetManager(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_CUSTOM_PRESETS = "remote_custom_presets"
        private const val KEY_ACTIVE_PRESET  = "remote_active_preset_name"
    }

    /** All presets: default at index 0, then custom presets. */
    fun allPresets(): List<RemoteKeyPreset> = listOf(RemoteKeyPreset.DMD_REMOTE_2) + loadCustomPresets()

    fun loadCustomPresets(): MutableList<RemoteKeyPreset> {
        val json = prefs.getString(KEY_CUSTOM_PRESETS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { RemoteKeyPreset.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveCustomPresets(presets: List<RemoteKeyPreset>) {
        val arr = JSONArray()
        presets.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CUSTOM_PRESETS, arr.toString()).apply()
    }

    fun getActivePreset(): RemoteKeyPreset {
        val name = prefs.getString(KEY_ACTIVE_PRESET, RemoteKeyPreset.DMD_REMOTE_2.name)
            ?: RemoteKeyPreset.DMD_REMOTE_2.name
        return allPresets().firstOrNull { it.name == name } ?: RemoteKeyPreset.DMD_REMOTE_2
    }

    fun setActivePreset(preset: RemoteKeyPreset) {
        prefs.edit().putString(KEY_ACTIVE_PRESET, preset.name).apply()
    }

    /**
     * Returns the next auto-generated "Custom N" name.
     * Finds the highest existing N among custom presets and returns N+1.
     */
    fun nextCustomName(): String {
        val existing = loadCustomPresets()
            .mapNotNull { Regex("""^Custom (\d+)$""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "Custom ${existing + 1}"
    }

    /**
     * Creates a new custom preset with an auto-generated name, copying keycodes from [source].
     * Persists the new preset and returns it.
     */
    fun createCustomCopy(source: RemoteKeyPreset): RemoteKeyPreset {
        val newPreset = RemoteKeyPreset(
            name = nextCustomName(),
            keycodes = source.keycodes.copyOf()
        )
        val customs = loadCustomPresets()
        customs.add(newPreset)
        saveCustomPresets(customs)
        return newPreset
    }

    /**
     * Replaces the stored version of a custom preset (matched by name) with updated data.
     * No-op if the preset does not exist in the custom list.
     */
    fun updateCustomPreset(preset: RemoteKeyPreset) {
        val customs = loadCustomPresets()
        val idx = customs.indexOfFirst { it.name == preset.name }
        if (idx >= 0) {
            customs[idx] = preset
            saveCustomPresets(customs)
        }
    }

    /**
     * Deletes a custom preset. No-op for "DMD Remote 2".
     * If the deleted preset was active, falls back to the default.
     */
    fun deleteCustomPreset(preset: RemoteKeyPreset) {
        if (preset.name == RemoteKeyPreset.DMD_REMOTE_2.name) return
        val wasActive = getActivePreset().name == preset.name
        val customs = loadCustomPresets().filter { it.name != preset.name }
        saveCustomPresets(customs)
        if (wasActive) setActivePreset(RemoteKeyPreset.DMD_REMOTE_2)
    }
}
