package com.androsnd.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.androsnd.db.entity.FolderEntity

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY sortOrder")
    fun getAllOrdered(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun getById(id: Long): FolderEntity?

    @Insert
    fun insert(folder: FolderEntity): Long

    @Query("DELETE FROM folders")
    fun deleteAll()
}
