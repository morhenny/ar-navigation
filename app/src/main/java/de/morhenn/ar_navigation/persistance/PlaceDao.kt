package de.morhenn.ar_navigation.persistance

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy.REPLACE
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {

    @Query("Select * from place")
    fun getPlaceList(): Flow<List<Place>>

    @Query("Select * from place where id is (:id)")
    fun getPlaceById(id: String): Place

    @Query("Select * from place where lat > :latSmall and lat < :latHigh and lng > :lngSmall and lng < :lngHigh")
    fun getPlaceListForCoordRange(latSmall: Double, latHigh: Double, lngSmall: Double, lngHigh: Double): Flow<List<Place>>

    @Insert(onConflict = REPLACE)
    fun insertPlace(place: Place)

    @Delete
    fun deletePlace(place: Place)

    @Query("Delete from place where id is (:id)")
    fun deletePlaceById(id: String)

    @Query("Delete from place")
    fun nukeTable()
}