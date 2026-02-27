package com.androsnd.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.androsnd.db.entity.SongEntity

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY folderId, sortOrder")
    fun getAllOrdered(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE folderId = :folderId ORDER BY sortOrder")
    fun getSongsInFolder(folderId: Long): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun getById(id: Long): SongEntity?

    @Insert
    fun insertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs")
    fun deleteAll()
}
