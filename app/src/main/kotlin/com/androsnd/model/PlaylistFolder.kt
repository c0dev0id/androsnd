package com.androsnd.model

data class PlaylistFolder(
    val name: String,
    val path: String,
    val songs: List<Int> = emptyList()
)
