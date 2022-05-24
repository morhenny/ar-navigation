package de.morhenn.ar_navigation.persistance

import de.morhenn.ar_navigation.util.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.SocketTimeoutException

class PlaceRepository {
    private val webservice = Webservice.getInstance().create(Webservice::class.java)
    private val placeDao = AppDatabase.getInstance().placeDao()

    companion object {
        private lateinit var INSTANCE: PlaceRepository

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

    fun newPlace(place: NewPlace) {
        val call = webservice.newPlace(place)
        call.enqueue(object : Callback<Place> {
            override fun onResponse(call: Call<Place>, response: Response<Place>) {
                FileLog.d(Webservice.TAG, "onResponse after sending newPlace, is successfull= ${response.isSuccessful} \n ${response.toString()}")
            }

            override fun onFailure(call: Call<Place>, t: Throwable) {
                FileLog.d(Webservice.TAG, "onFailure after sending newPlace, Throwable= $t")
            }
        })
        CoroutineScope(Dispatchers.IO).launch {
            refreshPlaces()
        }
    }

    fun updatePlace(place: Place) {
        val call = webservice.updatePlace(place)
        call.enqueue(object : Callback<Place> {
            override fun onResponse(call: Call<Place>, response: Response<Place>) {
                FileLog.d(Webservice.TAG, "onResponse after sending updatePlace, is successfull= ${response.isSuccessful} \n ${response.toString()}")
            }

            override fun onFailure(call: Call<Place>, t: Throwable) {
                FileLog.d(Webservice.TAG, "onFailure after sending updatePlace, Throwable= $t")
            }
        })
        CoroutineScope(Dispatchers.IO).launch {
            refreshPlaces()
        }
    }

    fun deletePlace(place: Place) {
        val call = webservice.deletePlace(place)
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                FileLog.d(Webservice.TAG, "onResponse after sending updatePlace, is successfull= ${response.isSuccessful} \n ${response.toString()}")
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                FileLog.d(Webservice.TAG, "onFailure after sending updatePlace, Throwable= $t")
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
            FileLog.e("WebService", "Could not connect to webserver")
        }
    }
}