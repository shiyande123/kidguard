package com.kidguard.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kidguard.data.db.ChildDao
import com.kidguard.data.db.KidGuardDatabase
import com.kidguard.data.db.LockLogDao
import com.kidguard.data.db.SettingsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KidGuardDatabase {
        return Room.databaseBuilder(
            context,
            KidGuardDatabase::class.java,
            "kidguard_database"
        )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Synchronous insert in the callback - runs on the same thread
                    db.execSQL(
                        "INSERT INTO settings (id, isEnabled, dailyTimeLimit, lockDelay, sensitivity, workingHoursStart, workingHoursEnd, notifyParent, soundEnabled) " +
                        "VALUES (1, 1, 120, 3, 0.7, 8, 22, 1, 1)"
                    )
                }
            })
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideChildDao(database: KidGuardDatabase): ChildDao {
        return database.childDao()
    }

    @Provides
    @Singleton
    fun provideLockLogDao(database: KidGuardDatabase): LockLogDao {
        return database.lockLogDao()
    }

    @Provides
    @Singleton
    fun provideSettingsDao(database: KidGuardDatabase): SettingsDao {
        return database.settingsDao()
    }
}
