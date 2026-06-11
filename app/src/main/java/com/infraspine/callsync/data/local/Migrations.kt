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
