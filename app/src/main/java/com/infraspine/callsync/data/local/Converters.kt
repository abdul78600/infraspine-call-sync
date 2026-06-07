package com.infraspine.callsync.data.local

import androidx.room.TypeConverter
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus

class Converters {

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.fromName(value)

    @TypeConverter
    fun fromCallType(type: CallType): String = type.name

    @TypeConverter
    fun toCallType(value: String): CallType = CallType.fromName(value)
}
