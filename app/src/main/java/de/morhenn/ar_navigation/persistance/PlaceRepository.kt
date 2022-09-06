package de.morhenn.ar_navigation.persistance

import de.morhenn.ar_navigation.util.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.math.cos

class PlaceRepository {
    private val webservice = Webservice.getInstance().create(Webservice::class.java)
    private val placeDao = AppDatabase.getInstance().placeDao()

    // Int is the amount of times, it will try again after failure
    private val newPlacesCache = HashMap<NewPlace, Int>()
    private val updatedPlacesCache = HashMap<Place, Int>()
    private val deletedPlacesCache = HashMap<Place, Int>()


    companion object {
        private lateinit var INSTANCE: PlaceRepository
        private const val CONNECTION_RETRY_DELAY = 10000L
        private const val CONNECTION_RETRY_MAX_ATTEMPTS = 10

        fun init() {
            if (!::INSTANCE.isInitialized) {
                synchronized(PlaceRepository::class) {
                    INSTANCE = PlaceRepository()
                }
            } else {
                throw IllegalStateException("You can't init twice!")
            }
        }

        fun getInstance(): PlaceRepository {
            if (Companion::INSTANCE.isInitialized) {
                return INSTANCE
            } else {
                throw IllegalStateException("Not initialized!")
            }
        }
    }

    fun getPlaces(): Flow<List<Place>> {
        CoroutineScope(Dispatchers.IO).launch {
            refreshPlaces()
        }
        return placeDao.getPlaceList()
    }



    //getPlaces that are around the given position in a radius of @radius meters
    fun getPlacesAroundLocation(latitude: Double, longitude: Double, radius: Double): Flow<List<Place>> {
        CoroutineScope(Dispatchers.IO).launch {
            refreshPlaces()
        }
        // 1° lat = 111.32 km
        // 1° long = 40075 km * cos( latitude ) / 360
        val latPerM = 1 / (111.32 * 1000)
        val longPerM = 1 / ((40075.0 * cos(latitude)/ 360) * 1000)
        val latDeltaInDegrees = radius * latPerM
        var longDeltaInDegrees = radius * longPerM

        val minLat = latitude - latDeltaInDegrees
        val maxLat = latitude + latDeltaInDegrees

        if(longDeltaInDegrees<0) longDeltaInDegrees*=-1

        val minLon = longitude - longDeltaInDegrees
        val maxLon = longitude + longDeltaInDegrees
        return placeDao.getPlaceListForCoordRange(minLat, maxLat, minLon, maxLon)
    }

    fun newPlace(place: NewPlace) {
        if (newPlacesCache.containsKey(place)) {
            val attemptsLeft = newPlacesCache[place]!!
            if (attemptsLeft > 0) {
                newPlacesCache[place] = attemptsLeft - 1
            } else {
                newPlacesCache.remove(place)
                return
            }
        }
        val call = webservice.newPlace(place)
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                CoroutineScope(Dispatchers.IO).launch {
                    refreshPlaces()
                }
                FileLog.d(Webservice.TAG, "onResponse after sending newPlace, is successfull= ${response.isSuccessful} \n ${response.toString()}")
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(CONNECTION_RETRY_DELAY)
                    newPlacesCache[place] = CONNECTION_RETRY_MAX_ATTEMPTS
                    newPlace(place)
                    FileLog.d(Webservice.TAG, "Called sending the new Place again after failure")
                }
                FileLog.d(Webservice.TAG, "onFailure after sending newPlace, Throwable= $t")
            }
        })
    }

    fun updatePlace(place: Place) {
        if (updatedPlacesCache.containsKey(place)) {
            val attemptsLeft = updatedPlacesCache[place]!!
            if (attemptsLeft > 0) {
                updatedPlacesCache[place] = attemptsLeft - 1
            } else {
                updatedPlacesCache.remove(place)
                return
            }
        }
        val call = webservice.updatePlace(place)
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                CoroutineScope(Dispatchers.IO).launch {
                    refreshPlaces()
                }
                FileLog.d(Webservice.TAG, "onResponse after sending updatePlace, is successfull= ${response.isSuccessful} \n ${response.toString()}")
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(CONNECTION_RETRY_DELAY)
                    updatedPlacesCache[place] = CONNECTION_RETRY_MAX_ATTEMPTS
                    updatePlace(place)
                    FileLog.d(Webservice.TAG, "Called sending the updated Place again after failure")
                }
                FileLog.d(Webservice.TAG, "onFailure after sending updatePlace, Throwable= $t")
            }
        })
    }

    fun deletePlace(place: Place) {
        if (deletedPlacesCache.containsKey(place)) {
            val attemptsLeft = deletedPlacesCache[place]!!
            if (attemptsLeft > 0) {
                deletedPlacesCache[place] = attemptsLeft - 1
            } else {
                deletedPlacesCache.remove(place)
                return
            }
        }
        val call = webservice.deletePlace(place)
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                CoroutineScope(Dispatchers.IO).launch {
                    refreshPlaces()
                }
                FileLog.d(Webservice.TAG, "onResponse after deleting Place, is successfull= ${response.isSuccessful} \n ${response.toString()}")
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(CONNECTION_RETRY_DELAY)
                    deletedPlacesCache[place] = CONNECTION_RETRY_MAX_ATTEMPTS
                    deletePlace(place)
                    FileLog.d(Webservice.TAG, "Called sending the deleted Place again after failure")
                }
                FileLog.d(Webservice.TAG, "onFailure after deleting Place, Throwable= $t")
            }
        })
    }

    private fun refreshPlaces() {
        try {
            val call = webservice.getAllPlaces()
            val response: List<Place>? = call.execute().body()
            response?.let {
                placeDao.nukeTable()
                for (place in it) {
                    placeDao.insertPlace(place)
                }
            }
        } catch (e: SocketTimeoutException) {
            FileLog.e("WebService", "Could not connect to webserver with $e")
        } catch (e: ConnectException) {
            FileLog.e("WebService", "Connection to webserver failed with $e")
        }
    }
}