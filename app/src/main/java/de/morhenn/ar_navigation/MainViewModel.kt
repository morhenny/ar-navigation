package de.morhenn.ar_navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import de.morhenn.ar_navigation.persistance.AppDatabase
import de.morhenn.ar_navigation.persistance.NewPlace
import de.morhenn.ar_navigation.persistance.Place
import de.morhenn.ar_navigation.persistance.PlaceRepository

class MainViewModel : ViewModel() {

    enum class NavState {
        NONE,
        CREATE_TO_AR_TO_TRY,
        MAPS_TO_AR_NEW,
        MAPS_TO_AR_NAV,
        MAPS_TO_AR_SEARCH,
        MAPS_TO_EDIT,

    }

    var navState = NavState.NONE

    private val db: AppDatabase = AppDatabase.getInstance()
    private val placeRepository = PlaceRepository.getInstance()
    var currentPlace: Place? = null
    var arDataString: String = ""

    private var lastLatLng: LatLng? = null

    var geoLat = 0.0
    var geoLng = 0.0
    var geoAlt = 0.0
    var geoHdg = 0.0

    val places = placeRepository.getPlaces().asLiveData()
    val placesMap = HashMap<Marker, Place>()

    var placesInRadius = placeRepository.getPlacesAroundLocation(0.0, 0.0, 1.0).asLiveData()

    fun uploadPlace(place: NewPlace) {
        placeRepository.newPlace(place)
    }

    fun updatePlace(place: Place) {
        placeRepository.updatePlace(place)
    }

    fun deletePlace(place: Place) {
        placeRepository.deletePlace(place)
    }

    fun updateCurrentPlace(marker: Marker) {
        currentPlace = placesMap[marker]
        arDataString = currentPlace?.ardata ?: ""
    }

    fun clearCurrentPlace() {
        currentPlace = null
        arDataString = ""
    }

    fun fetchPlaces() {
        placeRepository.getPlaces()
    }

    fun clearGeo() {
        geoHdg = 0.0
        geoAlt = 0.0
        geoLat = 0.0
        geoLng = 0.0
    }

    fun fetchPlacesAroundLocation(latLng: LatLng, searchRadius: Double) {
        lastLatLng = latLng
        placesInRadius = placeRepository.getPlacesAroundLocation(latLng.latitude, latLng.longitude, searchRadius).asLiveData()
    }

    fun fetchPlacesAroundLastLocation(searchRadius: Double) {
        lastLatLng?.let {
            placesInRadius = placeRepository.getPlacesAroundLocation(it.latitude, it.longitude, searchRadius).asLiveData()
        }
    }

}
