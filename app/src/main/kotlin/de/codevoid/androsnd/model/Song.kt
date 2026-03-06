package de.codevoid.androsnd.model

import android.net.Uri

data class Song(
    val uri: Uri,
    val displayName: String,
    val folderPath: String,
    val folderName: String,
    val duration: Long = 0L,
    val lastModified: Long = 0L
)
