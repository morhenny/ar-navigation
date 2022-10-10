package de.morhenn.ar_navigation.adapter

import android.view.View
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker
import de.morhenn.ar_navigation.MainViewModel
import de.morhenn.ar_navigation.databinding.InfoWindowBinding

class MyInfoWindowAdapter(var binding: InfoWindowBinding, var viewModel: MainViewModel) : GoogleMap.InfoWindowAdapter {

    override fun getInfoWindow(marker: Marker): View {
        val place = viewModel.placesMap[marker]

        if (place != null) {
            binding.infoName.text = place.name
            binding.infoDescription.text = place.description
            binding.infoAuthor.text = "By: ${place.author}"
            binding.infoLat.text = "Lat: ${place.lat}"
            binding.infoLong.text = "Lng: ${place.lng}"
            return binding.root
        } else {
            throw IllegalStateException("Info Window opened without having a selected place in the viewModel")
        }
    }

    override fun getInfoContents(marker: Marker): View? {
        return null
    }
}