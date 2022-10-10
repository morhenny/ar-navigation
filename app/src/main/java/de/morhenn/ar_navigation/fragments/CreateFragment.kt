package de.morhenn.ar_navigation.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import de.morhenn.ar_navigation.MainViewModel
import de.morhenn.ar_navigation.R
import de.morhenn.ar_navigation.databinding.FragmentCreateBinding
import de.morhenn.ar_navigation.model.ArRoute
import de.morhenn.ar_navigation.persistance.NewPlace
import de.morhenn.ar_navigation.persistance.Place
import de.morhenn.ar_navigation.util.FileLog
import de.morhenn.ar_navigation.util.GeoUtils
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class CreateFragment : Fragment(), OnMapReadyCallback {

    //best practise for using binding
    private var _binding: FragmentCreateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by navGraphViewModels(R.id.nav_graph_xml)

    private var locationProvider: FusedLocationProviderClient? = null
    private var map: GoogleMap? = null

    private var editUID = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        locationProvider = LocationServices.getFusedLocationProviderClient(requireContext())
        val mapFragment = childFragmentManager.findFragmentById(R.id.map_pick_location) as SupportMapFragment
        mapFragment.getMapAsync(this)

        var editPlace = viewModel.currentPlace
        when (viewModel.navState) {
            MainViewModel.NavState.MAPS_TO_EDIT -> {
                editPlace?.let {
                    editUID = it.id
                    with(binding) {
                        buttonDelete.visibility = View.VISIBLE
                        buttonCreate.text = getString(R.string.button_update_place)
                        inputName.setText(it.name)
                        inputLat.setText(it.lat.toString())
                        inputLng.setText(it.lng.toString())
                        inputAlt.setText(it.alt.toString())
                        inputHdg.setText(it.heading.toString())
                        inputDescription.setText(it.description)
                        inputAuthor.setText(it.author)
                        buttonTryArRoute.visibility = View.VISIBLE
                    }
                }
            }
            MainViewModel.NavState.CREATE_TO_AR_TO_TRY -> {
                editPlace?.let {
                    editUID = it.id
                    with(binding) {
                        buttonDelete.visibility = View.VISIBLE
                        buttonCreate.text = getString(R.string.button_update_place)
                        inputName.setText(it.name)
                        inputLat.setText(it.lat.toString())
                        inputLng.setText(it.lng.toString())
                        inputAlt.setText(it.alt.toString())
                        inputHdg.setText(it.heading.toString())
                        inputDescription.setText(it.description)
                        inputAuthor.setText(it.author)
                        buttonTryArRoute.visibility = View.VISIBLE
                    }
                }
            }
            MainViewModel.NavState.MAPS_TO_AR_NEW -> {
                with(binding) {
                    buttonCreate.text = getString(R.string.button_upload_place)
                    buttonInputArdata.visibility = View.VISIBLE
                    buttonTryArRoute.visibility = View.GONE
                    buttonDelete.visibility = View.GONE
                    binding.buttonInputArdata.text = getString(R.string.button_cancel_and_redo_ar)
                    inputLat.setText(viewModel.geoLat.toString())
                    inputLng.setText(viewModel.geoLng.toString())
                    inputAlt.setText(viewModel.geoAlt.toString())
                    inputHdg.setText(viewModel.geoHdg.toString())
                }
            }
            else -> throw IllegalStateException("Impossible NavState in CreateFragments onCreate")
        }
        binding.buttonInputArdata.setOnClickListener {
            if (viewModel.arDataString.isNotBlank()) {
                viewModel.arDataString = ""
            }
            findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToArFragment())
        }
        binding.buttonTryArRoute.setOnClickListener {
            viewModel.navState = MainViewModel.NavState.CREATE_TO_AR_TO_TRY
            editPlace?.let {
                viewModel.currentPlace = it
            }
            findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToArFragment())
        }

        binding.buttonCreate.setOnClickListener {
            val inputError = checkInputIfEmpty()
            if (!inputError) {
                if (editUID.isNotBlank()) {
                    editPlace = Place(
                        editUID,
                        binding.inputName.text.toString(),
                        binding.inputLat.text.toString().toDouble(),
                        binding.inputLng.text.toString().toDouble(),
                        binding.inputAlt.text.toString().toDouble(),
                        binding.inputHdg.text.toString().toDouble(),
                        binding.inputDescription.text.toString(),
                        binding.inputAuthor.text.toString(),
                        viewModel.arDataString
                    )
                    viewModel.updatePlace(editPlace!!)
                    findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToMapsFragment())
                    viewModel.fetchPlaces()
                } else {
                    val place = NewPlace(
                        binding.inputName.text.toString(),
                        binding.inputLat.text.toString().toDouble(),
                        binding.inputLng.text.toString().toDouble(),
                        binding.inputAlt.text.toString().toDouble(),
                        binding.inputHdg.text.toString().toDouble(),
                        binding.inputDescription.text.toString(),
                        binding.inputAuthor.text.toString(),
                        viewModel.arDataString
                    )
                    viewModel.uploadPlace(place)
                    findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToMapsFragment())
                    viewModel.fetchPlaces()
                }
            }
        }
        binding.buttonDelete.setOnClickListener {
            val inputError = checkInputIfEmpty()
            if (!inputError) {
                if (editUID.isNotBlank()) {
                    viewModel.deletePlace(editPlace!!)
                    viewModel.arDataString = ""
                    findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToMapsFragment())
                    viewModel.fetchPlaces()
                }
            }
        }
        super.onViewCreated(view, savedInstanceState)
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap.apply {
            uiSettings.isMapToolbarEnabled = false
            uiSettings.isCompassEnabled = true
            isMyLocationEnabled = true
            mapType = GoogleMap.MAP_TYPE_NORMAL
            isIndoorEnabled = true
        }
        val icon = BitmapDescriptorFactory.fromResource(R.drawable.ic_push_pin)

        if (binding.inputLat.text.toString().isNotBlank() && binding.inputLng.text.toString().isNotBlank()) { //If an anchor location is provided, display it as a marker on the map and center around it
            val latLng = LatLng(binding.inputLat.text.toString().toDouble(), binding.inputLng.text.toString().toDouble())
            map?.addMarker(MarkerOptions().position(latLng).title(getString(R.string.select_position_title)).icon(icon))?.showInfoWindow()
            val builder = CameraPosition.builder()
                .zoom(18f)
                .target(latLng)
            map?.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))

            try {
                val arRoute = Json.decodeFromString<ArRoute>(viewModel.arDataString)
                var lat = 0.0
                var lng = 0.0
                var heading = 0.0
                var lastPoint = latLng
                viewModel.currentPlace?.let { place ->
                    lat = place.lat
                    lng = place.lng
                    heading = place.heading
                } ?: run {
                    lat = viewModel.geoLat
                    lng = viewModel.geoLng
                    heading = viewModel.geoHdg
                }
                arRoute.pointsList.forEach {
                    val pointLatLng = GeoUtils.getLatLngByLocalCoordinateOffset(lat, lng, heading, it.position.x, it.position.z)
                    val polyline1 = map?.addPolyline(PolylineOptions()
                        .clickable(false)
                        .add(lastPoint)
                        .add(pointLatLng))
                    if (it.modelName == AugmentedRealityFragment.ModelName.TARGET) {
                        polyline1?.color = Color.RED
                    }
                    lastPoint = pointLatLng
                }
            } catch (e: Exception) {
                FileLog.e("TAG", "ArData could not be parsed: $e")
            }

        } else { //If no anchor location is provided, zoom the map on the users location instead
            locationProvider?.let {
                it.lastLocation.addOnSuccessListener { location ->
                    val latLng = LatLng(location.latitude, location.longitude)
                    val builder = CameraPosition.builder().zoom(18f).target(latLng)
                    map?.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
                }
            }
        }
    }

    private fun checkInputIfEmpty(): Boolean {
        var inputError = false
        if (binding.inputName.text.isNullOrBlank()) {
            binding.inputName.error = getString(R.string.error_cannot_be_empty)
            inputError = true
        }
        if (binding.inputDescription.text.isNullOrBlank()) {
            binding.inputDescription.error = getString(R.string.error_cannot_be_empty)
            inputError = true
        }
        if (binding.inputAuthor.text.isNullOrBlank()) {
            binding.inputAuthor.error = getString(R.string.error_cannot_be_empty)
            inputError = true
        }
        return inputError
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        map?.clear()
        map = null
        locationProvider = null
    }
}