package de.morhenn.ar_navigation.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import de.morhenn.ar_navigation.adapter.MyInfoWindowAdapter
import de.morhenn.ar_navigation.databinding.FragmentMapsBinding
import de.morhenn.ar_navigation.databinding.InfoWindowBinding
import de.morhenn.ar_navigation.util.Utils

class MapsFragment : Fragment(), OnMapReadyCallback {

    //best practise for using binding
    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!

    private lateinit var infoWindowAdapter: MyInfoWindowAdapter
    private var selectedMarker: Marker? = null
    private var locationProvider: FusedLocationProviderClient? = null

    private val viewModel: MainViewModel by navGraphViewModels(R.id.nav_graph_xml)
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private var centered = true

    private var map: GoogleMap? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        infoWindowAdapter = MyInfoWindowAdapter(InfoWindowBinding.inflate(inflater, container, false), viewModel)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            requireActivity().finish()
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        locationProvider = LocationServices.getFusedLocationProviderClient(requireContext())

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                (childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync(this)
            } else {
                Utils.toast("The app requires location permission to function, please enable them")
            }
        }
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED -> {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Utils.toast(getString(R.string.location_rationale))
            }
            else -> {
                (childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync(this)
            }
        }
        binding.mapAddFab.setOnClickListener {
            if (selectedMarker != null) {
                viewModel.updateCurrentPlace(selectedMarker!!)
                viewModel.navState = MainViewModel.NavState.MAPS_TO_EDIT
                findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToCreateFragment())
            } else {
                viewModel.clearCurrentPlace()
                viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_NEW
                findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
            }
        }
        binding.mapRouteFab.setOnClickListener {
            viewModel.updateCurrentPlace(selectedMarker!!)
            viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_NAV
            findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
        }
        binding.mapArSearchFab.setOnClickListener {
            viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_SEARCH
            findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
        }
        binding.mapMyLocationFab.setImageResource(R.drawable.ic_baseline_gps_not_fixed_24)
        binding.mapMyLocationFab.setOnClickListener { zoomOnMyLocation() }
        super.onViewCreated(view, savedInstanceState)
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap.apply {

            setInfoWindowAdapter(infoWindowAdapter)

            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isCompassEnabled = true
            isMyLocationEnabled = true
            mapType = GoogleMap.MAP_TYPE_NORMAL
            isIndoorEnabled = true
            setOnCameraMoveStartedListener {
                if (centered) {
                    centered = false
                } else {
                    binding.mapMyLocationFab.setImageResource(R.drawable.ic_baseline_gps_not_fixed_24)
                }
            }
            setOnMarkerClickListener { marker ->
                if (marker.isInfoWindowShown) {
                    marker.hideInfoWindow()
                } else {
                    selectedMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    selectedMarker = marker
                    marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    marker.showInfoWindow()
                    binding.mapRouteFab.visibility = View.VISIBLE
                    binding.mapAddFab.setImageResource(R.drawable.ic_baseline_edit_location_alt_24)
                }
                true
            }
            setOnInfoWindowClickListener { marker ->
                viewModel.updateCurrentPlace(marker)
                viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_NAV
                findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
            }
            setOnInfoWindowLongClickListener { marker ->
                viewModel.updateCurrentPlace(marker)
                viewModel.navState = MainViewModel.NavState.MAPS_TO_EDIT
                findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToCreateFragment())
            }
            setOnMapClickListener {
                selectedMarker?.let {
                    it.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    selectedMarker = null
                    binding.mapRouteFab.visibility = View.GONE
                    binding.mapAddFab.setImageResource(R.drawable.ic_baseline_add_location_alt_24)
                }
            }
        }

        viewModel.places.observe(viewLifecycleOwner) {
            binding.mapRouteFab.visibility = View.GONE
            binding.mapAddFab.setImageResource(R.drawable.ic_baseline_add_location_alt_24)
            map?.clear()
            selectedMarker = null
            viewModel.placesMap.clear()
            for (place in it) {
                val marker = map?.addMarker(MarkerOptions().position(LatLng(place.lat, place.lng)))
                viewModel.placesMap[marker!!] = place
            }
        }
        viewModel.fetchPlaces()
        zoomOnMyLocation()

        map?.let { map ->
            binding.mapMyLocationFab.setOnLongClickListener {
                if (map.mapType == GoogleMap.MAP_TYPE_NORMAL) {
                    map.mapType = GoogleMap.MAP_TYPE_SATELLITE
                } else {
                    map.mapType = GoogleMap.MAP_TYPE_NORMAL
                }
                true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        map?.isIndoorEnabled = false
        map?.clear()
        map = null
        _binding = null
        selectedMarker = null
    }

    private fun zoomOnMyLocation() {
        viewModel.fetchPlaces()
        locationProvider?.let {
            it.lastLocation.addOnSuccessListener { l ->
                l?.let { location ->
                    centered = true
                    val target = LatLng(location.latitude, location.longitude)
                    val builder = CameraPosition.builder()
                        .zoom(18f)
                        .target(target)
                    map?.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
                    binding.mapMyLocationFab.setImageResource(R.drawable.ic_baseline_gps_fixed_24)
                }
            }
        }
    }
}