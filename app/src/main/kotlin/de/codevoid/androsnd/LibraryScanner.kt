package de.codevoid.androsnd

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import de.codevoid.androsnd.model.Song

/**
 * Finds the music under a tree and reports what is there. Has no opinion about
 * ordering, indices or playback — that is [Library.of]'s job — so how the files
 * are reached and what the library is shaped like can change independently.
 */
interface LibraryScanner {
    /** [onProgress] receives the running song count as files are discovered. */
    fun scan(treeUri: Uri, onProgress: ((Int) -> Unit)? = null): List<ScannedFolder>
}

/** The real scanner: a depth-first walk over a Storage Access Framework tree. */
class SafLibraryScanner(private val context: Context) : LibraryScanner {

    override fun scan(treeUri: Uri, onProgress: ((Int) -> Unit)?): List<ScannedFolder> {
        val found = mutableListOf<ScannedFolder>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDisplayName(treeUri, rootDocId) ?: "Music"
        walk(treeUri, rootDocId, rootName, rootName, found, 0, onProgress)
        return found
    }

    private fun queryDisplayName(treeUri: Uri, documentId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    /**
     * Appends every folder holding audio, itself first and then its subfolders.
     * Takes and returns the running song count rather than keeping one in a field,
     * so a scan carries no state that could outlive it or be shared with another.
     */
    private fun walk(
        treeUri: Uri,
        parentDocId: String,
        folderDisplayName: String,
        parentPath: String,
        out: MutableList<ScannedFolder>,
        songsSoFar: Int,
        onProgress: ((Int) -> Unit)?
    ): Int {
        var songCount = songsSoFar
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val audioFiles = mutableListOf<Triple<String, String, Long>>() // docId, displayName, lastModified
        val subDirs = mutableListOf<Pair<String, String>>() // docId to displayName
        var coverDocId: String? = null

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val lastModIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx) ?: ""
                val lastMod = if (lastModIdx >= 0) cursor.getLong(lastModIdx) else 0L
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> subDirs.add(docId to name)
                    MediaFileNames.isAudio(name) -> audioFiles.add(Triple(docId, name, lastMod))
                    MediaFileNames.isCover(name) -> coverDocId = docId
                }
            }
        }

        // A folder with no audio of its own is not a library entry, but is still walked.
        if (audioFiles.isNotEmpty()) {
            val coverUri = coverDocId?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
            val songs = audioFiles.map { (docId, name, lastMod) ->
                Song(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                    displayName = name,
                    folderPath = parentPath,
                    folderName = folderDisplayName,
                    duration = 0L,
                    lastModified = lastMod
                )
            }
            repeat(songs.size) { onProgress?.invoke(++songCount) }
            out.add(ScannedFolder(folderDisplayName, parentPath, coverUri, songs))
        }

        for ((docId, name) in subDirs) {
            songCount = walk(treeUri, docId, name, "$parentPath/$name", out, songCount, onProgress)
        }
        return songCount
    }
}
