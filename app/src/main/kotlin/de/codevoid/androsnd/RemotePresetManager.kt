package de.codevoid.androsnd

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Manages storage of the two supported remote presets:
 *   - "DMD Remote 2": built-in default, never stored, always available.
 *   - "Custom": a single user-defined mapping stored in SharedPreferences.
 *
 * A boolean flag tracks which of the two is currently active.
 */
class RemotePresetManager(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_CUSTOM_PRESET = "remote_custom_preset"
        private const val KEY_USE_CUSTOM    = "remote_use_custom"
    }

    fun isCustomActive(): Boolean = prefs.getBoolean(KEY_USE_CUSTOM, false)

    fun setCustomActive(useCustom: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CUSTOM, useCustom).apply()
    }

    fun getActivePreset(): RemoteKeyPreset =
        if (isCustomActive()) getCustomPreset() else RemoteKeyPreset.DMD_REMOTE_2

    /**
     * Returns the stored custom preset, or a copy of DMD Remote 2 keycodes named "Custom"
     * if nothing has been saved yet.
     */
    fun getCustomPreset(): RemoteKeyPreset {
        val json = prefs.getString(KEY_CUSTOM_PRESET, null) ?: return defaultCustomPreset()
        return try {
            RemoteKeyPreset.fromJson(JSONObject(json))
        } catch (e: Exception) {
            defaultCustomPreset()
        }
    }

    fun saveCustomPreset(preset: RemoteKeyPreset) {
        prefs.edit().putString(KEY_CUSTOM_PRESET, preset.toJson().toString()).apply()
    }

    private fun defaultCustomPreset() =
        RemoteKeyPreset("Custom", RemoteKeyPreset.DMD_REMOTE_2.keycodes.copyOf())
}
