package de.morhenn.ar_navigation

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
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

class CreateFragment : Fragment(), OnMapReadyCallback {

    //best practise for using binding
    private var _binding: FragmentCreateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by navGraphViewModels(R.id.nav_graph_xml)

    private var locationProvider: FusedLocationProviderClient? = null
    private lateinit var map: GoogleMap
    private lateinit var marker: Marker
    private val lanternMarkerList = ArrayList<Marker>()
    private var lanternShown = true

    private var editUID = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCreateBinding.inflate(inflater, container, false)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            findNavController().navigate(CreateFragmentDirections.actionCreateFragmentToMapsFragment())
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        locationProvider = LocationServices.getFusedLocationProviderClient(requireContext())
        val mapFragment = childFragmentManager.findFragmentById(R.id.map_pick_location) as SupportMapFragment
        mapFragment.getMapAsync(this)

        var editPlace = viewModel.currentPlace
        editPlace?.let {
            editUID = it.id
            with(binding) {
                buttonDelete.visibility = View.VISIBLE
                buttonCreate.text = getString(R.string.button_update_place)
                inputName.setText(it.name)
                inputLat.setText(it.lat.toString())
                inputLng.setText(it.lng.toString())
                inputDescription.setText(it.description)
                inputAuthor.setText(it.author)
                buttonInputArdata.text = getString(R.string.button_edit_ar_data)
                buttonTryArRoute.visibility = View.VISIBLE
                checkHasAsRoute.setImageResource(R.drawable.ic_baseline_check_box_48)
            }
        } ?: run {
            binding.buttonCreate.text = getString(R.string.button_upload_place)
            binding.buttonDelete.visibility = View.GONE
        }

        if (viewModel.arDataString.isNotBlank()) {
            binding.checkHasAsRoute.setImageResource(R.drawable.ic_baseline_check_box_48)
            binding.buttonInputArdata.text = getString(R.string.button_edit_ar_data)
            binding.buttonTryArRoute.visibility = View.VISIBLE
        }
        binding.buttonInputArdata.setOnClickListener {
            if (viewModel.arDataString.isNotBlank()) {
                viewModel.arDataString = ""
                viewModel.navState = MainViewModel.NavState.CREATE_TO_AR_REDO
            } else {
                viewModel.navState = MainViewModel.NavState.CREATE_TO_AR_NEW
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
        map.mapType = GoogleMap.MAP_TYPE_SATELLITE
        val icon = BitmapDescriptorFactory.fromResource(R.drawable.ic_push_pin)

        if (editUID.isNotBlank()) {
            val latLng = LatLng(binding.inputLat.text.toString().toDouble(), binding.inputLng.text.toString().toDouble())
            map.addMarker(MarkerOptions().position(latLng).title(getString(R.string.select_position_title)).icon(icon))?.let { that -> marker = that }
            marker.showInfoWindow()
            val builder = CameraPosition.builder()
                .zoom(18f)
                .target(latLng)
            map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
        } else {
            locationProvider?.let {
                it.lastLocation.addOnSuccessListener { location ->
                    val latLng = LatLng(location.latitude, location.longitude)
                    map.addMarker(MarkerOptions().position(latLng).title(getString(R.string.select_position_title)).icon(icon))?.let { that -> marker = that }
                    val builder = CameraPosition.builder().zoom(18f).target(latLng)
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))

                }
            }
        }
        map.setOnCameraMoveListener {
            val latLng = map.projection.visibleRegion.latLngBounds.center
            marker.position = latLng
            binding.inputLat.setText(latLng.latitude.toString())
            binding.inputLng.setText(latLng.longitude.toString())
            if (map.cameraPosition.zoom < 15) {
                toggleLanternMarkers(turnOn = false)
            } else {
                toggleLanternMarkers(turnOn = true)
            }
        }

        binding.buttonSyncLocation.setOnClickListener {
            var inputError = false
            val lat = binding.inputLat.text.toString()
            val lng = binding.inputLng.text.toString()
            if (lat.isBlank()) {
                binding.inputLat.error = getString(R.string.error_cannot_be_empty)
                inputError = true
            }
            if (lng.isBlank()) {
                binding.inputLng.error = getString(R.string.error_cannot_be_empty)
                inputError = true
            }
            if (!inputError) {
                marker.position = LatLng(lat.toDouble(), lng.toDouble())
                val builder = CameraPosition.builder().zoom(18f).target(marker.position)
                map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
            }
        }
        loadLanternMarker()
    }

    private fun toggleLanternMarkers(turnOn: Boolean) {
        if (turnOn && !lanternShown) {
            lanternMarkerList.forEach {
                it.isVisible = true
            }
            lanternShown = true
        } else if (!turnOn && lanternShown) {
            lanternMarkerList.forEach {
                it.isVisible = false
            }
            lanternShown = false
        }
    }

    private fun loadLanternMarker() {
        val icon = BitmapDescriptorFactory.fromResource(R.drawable.ic_lamp_lightbulp)
        val coordList = ArrayList<LatLng>()
        with(coordList) {
            //Rostocker Straße
            add(LatLng(52.53114, 13.32655))
            add(LatLng(52.53067000, 13.32657000))
            add(LatLng(52.53150100, 13.32654000))
            add(LatLng(52.53088679, 13.32674041))
            add(LatLng(52.53200403, 13.32650811))
            add(LatLng(52.52867000, 13.32684000))
            add(LatLng(52.52776407, 13.32688110))
            add(LatLng(52.53175000, 13.32668000))
            add(LatLng(52.53004443, 13.32677980))
            add(LatLng(52.53226400, 13.32669173))
            add(LatLng(52.52845067, 13.32666457))
            add(LatLng(52.53023000, 13.32659000))
            add(LatLng(52.53043075, 13.32678000))
            add(LatLng(52.52896000, 13.32664000))
            add(LatLng(52.52820265, 13.32686073))
            add(LatLng(52.52956000, 13.32680100))
            add(LatLng(52.52797529, 13.32668420))
            add(LatLng(52.52979000, 13.32660200))
            add(LatLng(52.53247383, 13.32668397))
            add(LatLng(52.52933000, 13.32662000))
            add(LatLng(52.52911000, 13.32682000))
            add(LatLng(52.53133169, 13.32672902))

            //Wittstocker Straße
            add(LatLng(52.53085567, 13.32616190))
            add(LatLng(52.53110633, 13.32760931))
            add(LatLng(52.53092720, 13.32557935))
            add(LatLng(52.53107162, 13.32715693))
            add(LatLng(52.53081726, 13.32571309))
            add(LatLng(52.53096524, 13.32605969))
            add(LatLng(52.53099423, 13.32764926))
            add(LatLng(52.53115477, 13.32805524))
            add(LatLng(52.53088879, 13.32529244))
            add(LatLng(52.53076223, 13.32519919))

            //Ecke Berlichingenstraße
            add(LatLng(52.53074108, 13.32491396))
            add(LatLng(52.53031458, 13.32491381))
        }
        coordList.forEach {
            map.addMarker(MarkerOptions().position(it).title("Laterne").icon(icon))?.let { marker ->
                lanternMarkerList.add(marker)
            }
        }
        lanternShown = true
    }


    private fun checkInputIfEmpty(): Boolean {
        var inputError = false
        if (binding.inputName.text.isNullOrBlank()) {
            binding.inputName.error = getString(R.string.error_cannot_be_empty)
            inputError = true
        }
        if (binding.inputLat.text.isNullOrBlank()) {
            binding.inputLat.error = getString(R.string.error_cannot_be_empty)
            inputError = true
        }
        if (binding.inputLng.text.isNullOrBlank()) {
            binding.inputLng.error = getString(R.string.error_cannot_be_empty)
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
        /*if (binding.buttonInputArdata.text.equals(getString(R.string.button_create_ar_data))) {
            Utils.toast("Please set up the AR Route first")
            binding.buttonInputArdata.error = getString(R.string.error_cannot_be_empty)
            inputError = true
        }*/
        return inputError
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.currentPlace = null
        _binding = null
    }

}