package de.morhenn.ar_navigation.persistance

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Place::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao

    companion object {
        private lateinit var INSTANCE: AppDatabase

        fun init(context: Context) {
            if (!::INSTANCE.isInitialized) {
                synchronized(AppDatabase::class) {
                    INSTANCE = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "PlacesDB").build()
                }
            } else {
                throw IllegalStateException("You can't init twice!")
            }
        }

        fun getInstance(): AppDatabase {
            if (::INSTANCE.isInitialized) {
                return INSTANCE
            } else {
                throw IllegalStateException("Not initialized!")
            }
        }
    }
}