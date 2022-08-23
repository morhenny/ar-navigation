package de.morhenn.ar_navigation

import android.annotation.SuppressLint
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
import de.morhenn.ar_navigation.databinding.FragmentCreateBinding
import de.morhenn.ar_navigation.persistance.NewPlace
import de.morhenn.ar_navigation.persistance.Place
import de.morhenn.ar_navigation.util.FileLog

class CreateFragment : Fragment(), OnMapReadyCallback {

    //best practise for using binding
    private var _binding: FragmentCreateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by navGraphViewModels(R.id.nav_graph_xml)

    private var locationProvider: FusedLocationProviderClient? = null
    private lateinit var map: GoogleMap
    private lateinit var marker: Marker

    private var editUID = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCreateBinding.inflate(inflater, container, false)
//        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
//            findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToMapsFragment())
//        }
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
                        buttonInputArdata.text = getString(R.string.button_edit_ar_data)
                        buttonTryArRoute.visibility = View.VISIBLE
                        checkHasAsRoute.setImageResource(R.drawable.ic_baseline_check_box_48)
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
                        buttonInputArdata.text = getString(R.string.button_edit_ar_data)
                        buttonTryArRoute.visibility = View.VISIBLE
                        checkHasAsRoute.setImageResource(R.drawable.ic_baseline_check_box_48)
                    }
                }

            }
            MainViewModel.NavState.MAPS_TO_AR_NEW -> {
                with(binding) {
                    buttonCreate.text = getString(R.string.button_upload_place)
                    buttonTryArRoute.visibility = View.GONE
                    buttonDelete.visibility = View.GONE
                    binding.buttonInputArdata.text = getString(R.string.button_cancel_and_redo_ar)
                    inputLat.setText(viewModel.geoLat.toString())
                    inputLng.setText(viewModel.geoLng.toString())
                    inputAlt.setText(viewModel.geoAlt.toString())
                    inputHdg.setText(viewModel.geoHdg.toString())
                }
            }
            else -> FileLog.e("O_O", "Wrong Navstate in CreateFragments onCreate")
        }

        if (viewModel.arDataString.isNotBlank()) {
            binding.checkHasAsRoute.setImageResource(R.drawable.ic_baseline_check_box_48)
        }
        binding.buttonInputArdata.setOnClickListener {
            if (viewModel.arDataString.isNotBlank()) {
                viewModel.arDataString = ""
            } else {
                FileLog.e("O_O", "wrong app state on click of Redo Route button")
            }
            findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToArFragment())
        }
        binding.buttonTryArRoute.setOnClickListener {
            viewModel.navState = MainViewModel.NavState.CREATE_TO_AR_TO_TRY
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
        map = googleMap
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.isMyLocationEnabled = true
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        val icon = BitmapDescriptorFactory.fromResource(R.drawable.ic_push_pin)

        if (binding.inputLat.text.toString().isNotBlank() && binding.inputLng.text.toString().isNotBlank()) { //If an anchor location is provided, display it as a marker on the map and center around it
            val latLng = LatLng(binding.inputLat.text.toString().toDouble(), binding.inputLng.text.toString().toDouble())
            map.addMarker(MarkerOptions().position(latLng).title(getString(R.string.select_position_title)).icon(icon))?.let { that -> marker = that }
            marker.showInfoWindow()
            val builder = CameraPosition.builder()
                .zoom(18f)
                .target(latLng)
            map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
        } else { //If no anchor location is provided, zoom the map on the users location instead
            locationProvider?.let {
                it.lastLocation.addOnSuccessListener { location ->
                    FileLog.d("O_O", "Used new location in create")
                    val latLng = LatLng(location.latitude, location.longitude)
                    val builder = CameraPosition.builder().zoom(18f).target(latLng)
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
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

    override fun onDestroy() {
        super.onDestroy()
        viewModel.currentPlace = null
        _binding = null
    }

}