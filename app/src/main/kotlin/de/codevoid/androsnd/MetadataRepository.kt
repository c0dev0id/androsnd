package de.codevoid.androsnd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM
import android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
import android.net.Uri
import android.util.Log
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class MetadataRepository(private val context: Context) {

    companion object {
        private const val TAG = "MetadataRepository"
    }

    val db = MetadataDb(context)
    private val artDir = File(context.cacheDir, "album_art").also { it.mkdirs() }
    private var enrichJob: Job? = null

    fun artFileForFolder(folderPath: String): File =
        File(artDir, "${folderPath.hashCode()}.jpg")

    private fun currentArtFile(): File = File(artDir, "current_art.jpg")

    internal suspend fun fetchText(song: Song): SongMetadata =
        db.get(song.uri.toString(), song.lastModified) ?: enrichText(song)

    internal suspend fun loadCurrentArt(song: Song): Bitmap? {
        val folderArtFile = artFileForFolder(song.folderPath)
        if (folderArtFile.exists()) {
            val bmp = BitmapFactory.decodeFile(folderArtFile.absolutePath) ?: return null
            val scaled = scaleBitmapForSession(bmp)
            saveToFile(scaled, currentArtFile(), notifyChange = true)
            if (scaled !== bmp) bmp.recycle()
            return scaled
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, song.uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bmp = decodeBitmapWithSampling(bytes, 512) ?: return null
            val scaled = scaleBitmapForSession(bmp)
            saveToFile(scaled, currentArtFile(), notifyChange = true)
            if (scaled !== bmp) bmp.recycle()
            scaled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load art for ${song.displayName}", e)
            null
        } finally {
            retriever.release()
        }
    }

    fun startEnrichment(
        scope: CoroutineScope,
        songs: List<Song>,
        foldersByPath: Map<String, PlaylistFolder>,
        currentIdx: Int,
        onTextReady: (idx: Int, meta: SongMetadata) -> Unit,
        onArtReady:  (folderPath: String) -> Unit,
        onComplete:  () -> Unit = {}
    ) {
        enrichJob?.cancel()
        enrichJob = scope.launch(Dispatchers.IO) {
            // Single semaphore serialises all MediaMetadataRetriever usage so it
            // doesn't compete with MediaPlayer.prepareAsync() for native media slots.
            val retrieverSem = Semaphore(1)

            // Priority: current song first, blocking
            songs.getOrNull(currentIdx)?.let { song ->
                val meta = enrichText(song)
                withContext(Dispatchers.Main) { onTextReady(currentIdx, meta) }
            }

            // All others: parallel dispatch, but at most one retriever open at a time
            val textJobs = songs.indices.filter { it != currentIdx }.map { idx ->
                launch {
                    retrieverSem.withPermit {
                        val meta = enrichText(songs[idx])
                        withContext(Dispatchers.Main) { onTextReady(idx, meta) }
                    }
                }
            }

            // Art: one coroutine per unique folder, same serialised retriever slot
            val artJobs = songs.map { it.folderPath }.distinct().map { folderPath ->
                launch {
                    retrieverSem.withPermit {
                        enrichArt(folderPath, songs, foldersByPath[folderPath])
                        withContext(Dispatchers.Main) { onArtReady(folderPath) }
                    }
                }
            }

            textJobs.forEach { it.join() }
            artJobs.forEach  { it.join() }
            db.cleanup(songs.map { it.uri.toString() }.toSet())
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun cancelEnrichment() {
        enrichJob?.cancel()
        enrichJob = null
    }

    fun close() {
        enrichJob?.cancel()
        db.close()
    }

    private suspend fun enrichText(song: Song): SongMetadata {
        db.get(song.uri.toString(), song.lastModified)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, song.uri)
            SongMetadata(
                retriever.extractMetadata(METADATA_KEY_TITLE)    ?: song.displayName,
                retriever.extractMetadata(METADATA_KEY_ARTIST)   ?: "",
                retriever.extractMetadata(METADATA_KEY_ALBUM)    ?: "",
                retriever.extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ).also { db.upsert(song.uri.toString(), song.lastModified, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata for ${song.displayName}", e)
            SongMetadata(song.displayName, "", "", 0L)
        } finally {
            retriever.release()
        }
    }

    private fun enrichArt(folderPath: String, songs: List<Song>, folder: PlaylistFolder?) {
        val artFile = artFileForFolder(folderPath)
        if (artFile.exists()) return
        // 1. Folder cover URI
        folder?.coverUri?.let { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    decodeBitmapWithSampling(stream.readBytes(), 256)?.let { bmp ->
                        saveToFile(bmp, artFile)
                        bmp.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load folder cover for $folderPath", e)
            }
            return
        }
        // 2. First embedded picture in folder
        songs.filter { it.folderPath == folderPath }.forEach { song ->
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, song.uri)
                val bytes = r.embeddedPicture ?: return@forEach
                decodeBitmapWithSampling(bytes, 256)?.let { bmp ->
                    saveToFile(bmp, artFile)
                    bmp.recycle()
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read embedded art from ${song.displayName}", e)
            } finally {
                r.release()
            }
        }
    }

    private fun decodeBitmapWithSampling(bytes: ByteArray, maxPx: Int = 512): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxPx)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun scaleBitmapForSession(bitmap: Bitmap, maxPx: Int = 256): Bitmap {
        if (bitmap.width <= maxPx && bitmap.height <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxOf(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
    }

    private fun saveToFile(bitmap: Bitmap, file: File, notifyChange: Boolean = false) {
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (notifyChange) {
                val uri = Uri.parse("content://de.codevoid.androsnd.albumart/${file.name}")
                context.contentResolver.notifyChange(uri, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save bitmap to ${file.name}", e)
        }
    }
}
