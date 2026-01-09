package com.belomorie.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database для хранения результатов анализа
 */
@Database(
    entities = [TrackingEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BelomorieDatabase : RoomDatabase() {
    
    abstract fun trackingDao(): TrackingDao
    
    companion object {
        @Volatile
        private var INSTANCE: BelomorieDatabase? = null
        
        fun getDatabase(context: Context): BelomorieDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BelomorieDatabase::class.java,
                    "belomorie_database"
                )
                    .fallbackToDestructiveMigration() // Для разработки
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}







