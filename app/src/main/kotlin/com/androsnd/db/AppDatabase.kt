package com.androsnd.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.androsnd.db.dao.FolderDao
import com.androsnd.db.dao.SongDao
import com.androsnd.db.entity.FolderEntity
import com.androsnd.db.entity.SongEntity

@Database(entities = [FolderEntity::class, SongEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "androsnd.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
