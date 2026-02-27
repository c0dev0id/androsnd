package com.androsnd.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.androsnd.db.AppDatabase
import com.androsnd.db.entity.FolderEntity
import com.androsnd.db.entity.SongEntity

class LibraryScanner(private val context: Context, private val db: AppDatabase) {

    companion object {
        private const val TAG = "LibraryScanner"
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "flac", "aac", "m4a", "opus")
    }

    private val coverArtExtractor = CoverArtExtractor(context)

    fun scan(treeUri: Uri) {
        db.runInTransaction {
            db.songDao().deleteAll()
            db.folderDao().deleteAll()
        }

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDisplayName(treeUri, rootDocId) ?: "Music"

        val collectedFolders = mutableListOf<FolderInfo>()
        collectFolders(treeUri, rootDocId, rootName, rootName, collectedFolders)
        collectedFolders.sortBy { it.name }

        for ((sortOrder, folderInfo) in collectedFolders.withIndex()) {
            if (folderInfo.audioFiles.isEmpty()) continue

            val folderEntity = FolderEntity(
                name = folderInfo.name,
                path = folderInfo.path,
                coverArtPath = null,
                sortOrder = sortOrder
            )
            val folderId = db.folderDao().insert(folderEntity)

            val sortedAudio = folderInfo.audioFiles
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })

            val songs = sortedAudio.mapIndexed { songOrder, (docId, name) ->
                val songUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val meta = extractTrackMetadata(songUri, name)
                val coverArtPath = coverArtExtractor.extractForSong(
                    meta.embeddedPictureBytes,
                    folderInfo.path,
                    treeUri,
                    folderInfo.docId
                )
                SongEntity(
                    uri = songUri.toString(),
                    displayName = name,
                    title = meta.title,
                    artist = meta.artist,
                    album = meta.album,
                    duration = meta.duration,
                    folderId = folderId,
                    sortOrder = songOrder,
                    coverArtPath = coverArtPath
                )
            }
            db.songDao().insertAll(songs)
        }
    }

    private data class TrackMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val embeddedPictureBytes: ByteArray?
    )

    private fun extractTrackMetadata(uri: Uri, displayName: String): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: displayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val embeddedPictureBytes = retriever.embeddedPicture
            TrackMetadata(title, artist, album, duration, embeddedPictureBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata for $displayName", e)
            TrackMetadata(displayName, "", "", 0L, null)
        } finally {
            retriever.release()
        }
    }

    private data class FolderInfo(
        val docId: String,
        val name: String,
        val path: String,
        val audioFiles: List<Pair<String, String>>
    )

    private fun collectFolders(
        treeUri: Uri,
        parentDocId: String,
        folderDisplayName: String,
        parentPath: String,
        result: MutableList<FolderInfo>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val audioFiles = mutableListOf<Pair<String, String>>()
        val subDirs = mutableListOf<Pair<String, String>>()

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> subDirs.add(docId to name)
                        isAudioFile(name) -> audioFiles.add(docId to name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list children of $parentPath", e)
        }

        if (audioFiles.isNotEmpty()) {
            result.add(FolderInfo(parentDocId, folderDisplayName, parentPath, audioFiles))
        }

        for ((docId, name) in subDirs) {
            collectFolders(treeUri, docId, name, "$parentPath/$name", result)
        }
    }

    private fun queryDisplayName(treeUri: Uri, documentId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }
}
