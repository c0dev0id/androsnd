package com.androsnd.model

import android.net.Uri

data class Song(
    val uri: Uri,
    val displayName: String,
    val folderPath: String,
    val folderName: String
)
