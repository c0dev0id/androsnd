package de.codevoid.androsnd

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.codevoid.androsnd.model.SongMetadata

class MetadataDb(context: Context) : SQLiteOpenHelper(context, "metadata.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE songs (
                uri           TEXT PRIMARY KEY,
                title         TEXT NOT NULL,
                artist        TEXT NOT NULL,
                album         TEXT NOT NULL,
                duration      INTEGER NOT NULL,
                last_modified INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS songs")
        onCreate(db)
    }

    fun get(uri: String, lastModified: Long): SongMetadata? {
        if (lastModified <= 0L) return null
        readableDatabase.query(
            "songs", arrayOf("title", "artist", "album", "duration", "last_modified"),
            "uri = ?", arrayOf(uri), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.getLong(cursor.getColumnIndexOrThrow("last_modified")) != lastModified) return null
            return SongMetadata(
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("artist")),
                cursor.getString(cursor.getColumnIndexOrThrow("album")),
                cursor.getLong(cursor.getColumnIndexOrThrow("duration"))
            )
        }
    }

    fun upsert(uri: String, lastModified: Long, meta: SongMetadata) {
        val cv = ContentValues().apply {
            put("uri", uri)
            put("title", meta.title)
            put("artist", meta.artist)
            put("album", meta.album)
            put("duration", meta.duration)
            put("last_modified", lastModified)
        }
        writableDatabase.insertWithOnConflict("songs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun cleanup(validUris: Set<String>) {
        if (validUris.isEmpty()) {
            writableDatabase.delete("songs", null, null)
            return
        }
        val placeholders = validUris.joinToString(",") { "?" }
        writableDatabase.delete("songs", "uri NOT IN ($placeholders)", validUris.toTypedArray())
    }
}
