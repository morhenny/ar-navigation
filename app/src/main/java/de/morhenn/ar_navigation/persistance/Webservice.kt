package de.morhenn.ar_navigation.persistance

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*


interface Webservice {

    companion object {
        private lateinit var INSTANCE: Retrofit
        const val TAG = "WebService"

        fun init() {
            if (!::INSTANCE.isInitialized) {
                synchronized(Webservice::class) {
                    INSTANCE = Retrofit.Builder()
                        .baseUrl("http://192.168.178.74:8080/")
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                }
            } else {
                throw IllegalStateException("You can't init twice!")
            }
        }

        fun getInstance(): Retrofit {
            if (Companion::INSTANCE.isInitialized) {
                return INSTANCE
            } else {
                throw IllegalStateException("Not initialized!")
            }
        }
    }

    @GET("places")
    fun getAllPlaces(): Call<List<Place>>

    @POST("places")
    @Headers("Content-Type: application/json")
    fun newPlace(@Body place: NewPlace): Call<Place>

    @POST("places")
    @Headers("Content-Type: application/json")
    fun updatePlace(@Body place: Place): Call<Place>

    @HTTP(method = "DELETE", path = "places", hasBody = true)
    @Headers("Content-Type: application/json")
    fun deletePlace(@Body place: Place): Call<Void>
}