package de.codevoid.androsnd

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores one complete remote-control key mapping.
 *
 * The [keycodes] array has exactly [ACTION_COUNT] entries, one per action in this fixed order:
 *   0 = Volume Up
 *   1 = Volume Down
 *   2 = Back / Play-Pause  (short=play/pause or close settings; long=move app to background)
 *   3 = Navigate Up
 *   4 = Navigate Down
 *   5 = Navigate Left
 *   6 = Navigate Right
 *   7 = Confirm / Select
 */
data class RemoteKeyPreset(
    val name: String,
    val keycodes: IntArray
) {
    companion object {
        const val ACTION_COUNT = 8

        val ACTION_NAMES = listOf(
            "Volume Up",
            "Volume Down",
            "Back / Play-Pause",
            "Navigate Up",
            "Navigate Down",
            "Navigate Left",
            "Navigate Right",
            "Confirm"
        )

        /** The built-in default preset. Never stored in JSON, never deletable. */
        val DMD_REMOTE_2 = RemoteKeyPreset(
            name = "DMD Remote 2",
            keycodes = intArrayOf(
                KeyEvent.KEYCODE_F6,          // 0: Volume Up
                KeyEvent.KEYCODE_F7,          // 1: Volume Down
                KeyEvent.KEYCODE_ESCAPE,      // 2: Back / Play-Pause
                KeyEvent.KEYCODE_DPAD_UP,     // 3: Navigate Up
                KeyEvent.KEYCODE_DPAD_DOWN,   // 4: Navigate Down
                KeyEvent.KEYCODE_DPAD_LEFT,   // 5: Navigate Left
                KeyEvent.KEYCODE_DPAD_RIGHT,  // 6: Navigate Right
                KeyEvent.KEYCODE_ENTER        // 7: Confirm
            )
        )

        fun fromJson(json: JSONObject): RemoteKeyPreset {
            val name = json.getString("name")
            val arr = json.getJSONArray("keycodes")
            val keycodes = IntArray(ACTION_COUNT) { arr.getInt(it) }
            return RemoteKeyPreset(name, keycodes)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        val arr = JSONArray()
        keycodes.forEach { arr.put(it) }
        put("keycodes", arr)
    }

    // IntArray requires explicit equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteKeyPreset) return false
        return name == other.name && keycodes.contentEquals(other.keycodes)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + keycodes.contentHashCode()
}
