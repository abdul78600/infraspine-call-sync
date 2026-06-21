package com.infraspine.callsync.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the recordings.fileHash column used for recording dedup keys. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recordings ADD COLUMN fileHash TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recordings_fileHash ON recordings(fileHash)")
    }
}

/** Adds the sync_history table for tracking past sync sessions. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                syncedAt INTEGER NOT NULL,
                recordingsUploaded INTEGER NOT NULL,
                recordingsFailed INTEGER NOT NULL,
                recordingsSkipped INTEGER NOT NULL,
                callLogsUploaded INTEGER NOT NULL,
                callLogsFailed INTEGER NOT NULL
            )"""
        )
    }
}
