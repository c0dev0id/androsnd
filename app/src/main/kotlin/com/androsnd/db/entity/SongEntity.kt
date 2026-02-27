package com.androsnd.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("folderId")]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val folderId: Long,
    val sortOrder: Int,
    val coverArtPath: String?
)
