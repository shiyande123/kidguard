package com.kidguard.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kidguard.data.model.Child
import com.kidguard.data.model.LockLog
import com.kidguard.data.model.Settings

@Database(
    entities = [Child::class, LockLog::class, Settings::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class KidGuardDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun lockLogDao(): LockLogDao
    abstract fun settingsDao(): SettingsDao
}
