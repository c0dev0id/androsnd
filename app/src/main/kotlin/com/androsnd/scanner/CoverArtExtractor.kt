package com.androsnd.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

class CoverArtExtractor(private val context: Context) {

    companion object {
        private const val TAG = "CoverArtExtractor"
        private val COVER_NAMES = setOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png",
            "album.jpg", "album.png", "front.jpg", "front.png",
            "artwork.jpg", "artwork.png"
        )
    }

    private val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
    private val folderCoverCache = mutableMapOf<String, String?>()

    fun extractForSong(
        embeddedPictureBytes: ByteArray?,
        folderPath: String,
        treeUri: Uri,
        folderDocId: String
    ): String? {
        if (embeddedPictureBytes != null) {
            val saved = saveEmbeddedArt(embeddedPictureBytes)
            if (saved != null) return saved
        }
        return findFolderCover(folderPath, treeUri, folderDocId)
    }

    private fun saveEmbeddedArt(bytes: ByteArray): String? {
        return try {
            val hash = bytes.contentHashCode().toUInt().toString(16)
            val file = File(coversDir, "$hash.jpg")
            if (!file.exists()) {
                file.outputStream().use { it.write(bytes) }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save embedded art", e)
            null
        }
    }

    private fun findFolderCover(folderPath: String, treeUri: Uri, folderDocId: String): String? {
        folderCoverCache[folderPath]?.let { return it }
        val result = scanFolderForCover(treeUri, folderDocId, folderPath)
        folderCoverCache[folderPath] = result
        return result
    }

    private fun scanFolderForCover(treeUri: Uri, folderDocId: String, folderPath: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    if (name.lowercase() in COVER_NAMES) {
                        val hash = folderPath.hashCode().toUInt().toString(16)
                        val ext = name.substringAfterLast('.', "jpg")
                        val file = File(coversDir, "folder_$hash.$ext")
                        if (!file.exists()) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            context.contentResolver.openInputStream(docUri)?.use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        return file.absolutePath
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan folder for cover art: $folderPath", e)
        }
        return null
    }
}
