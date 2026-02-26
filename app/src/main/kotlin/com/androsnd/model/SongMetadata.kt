package com.androsnd.model

import android.graphics.Bitmap

data class SongMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val coverArt: Bitmap?
)
