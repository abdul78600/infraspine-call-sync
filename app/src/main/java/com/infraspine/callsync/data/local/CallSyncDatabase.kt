package com.infraspine.callsync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.data.local.entity.RecordingEntity

@Database(
    entities = [RecordingEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CallSyncDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    companion object {
        private const val DB_NAME = "callsync.db"

        @Volatile
        private var instance: CallSyncDatabase? = null

        fun getInstance(context: Context): CallSyncDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallSyncDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
