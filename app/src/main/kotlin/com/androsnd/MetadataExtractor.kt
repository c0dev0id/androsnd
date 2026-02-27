package com.androsnd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.annotation.WorkerThread
import com.androsnd.model.Song
import com.androsnd.model.SongMetadata

class MetadataExtractor(private val context: Context) {

    companion object {
        private const val TAG = "MetadataExtractor"
        const val MAX_COVER_ART_SIZE = 512
    }

    @WorkerThread
    fun extract(song: Song): SongMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, song.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.displayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val artBytes = retriever.embeddedPicture
            val coverArt = if (artBytes != null) decodeSampledBitmap(artBytes, MAX_COVER_ART_SIZE) else null
            SongMetadata(title, artist, album, duration, coverArt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata for ${song.displayName}", e)
            SongMetadata(song.displayName, "", "", 0L, null)
        } finally {
            retriever.release()
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxSize)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        if (height > maxSize || width > maxSize) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / sampleSize >= maxSize && halfWidth / sampleSize >= maxSize) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }
}
