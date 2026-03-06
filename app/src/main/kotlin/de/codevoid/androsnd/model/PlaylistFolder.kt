package de.codevoid.androsnd.model

import android.net.Uri

data class PlaylistFolder(
    val name: String,
    val path: String,
    val songs: MutableList<Int> = mutableListOf(),
    val coverUri: Uri? = null
)
