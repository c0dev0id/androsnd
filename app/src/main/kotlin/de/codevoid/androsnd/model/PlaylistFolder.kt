package de.codevoid.androsnd.model

data class PlaylistFolder(
    val name: String,
    val path: String,
    val songs: MutableList<Int> = mutableListOf()
)
