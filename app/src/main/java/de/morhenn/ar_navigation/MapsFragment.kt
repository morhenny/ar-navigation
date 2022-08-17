package de.morhenn.ar_navigation

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import de.morhenn.ar_navigation.databinding.FragmentMapsBinding
import de.morhenn.ar_navigation.databinding.InfoWindowBinding
import de.morhenn.ar_navigation.util.FileLog
import de.morhenn.ar_navigation.util.Utils

class MapsFragment : Fragment(), OnMapReadyCallback, SensorEventListener {

    private val MAX_DISTANCE_TO_START = 500 //Distance in m between marker location and GPS position, to be able to start AR-Navigation


    //best practise for using binding
    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!

    private lateinit var infoWindowAdapter: MyInfoWindowAdapter
    private lateinit var routeFab: FloatingActionButton
    private lateinit var searchInArFab: FloatingActionButton
    private lateinit var myLocationFab: FloatingActionButton
    private lateinit var createFab: FloatingActionButton
    private var selectedMarker: Marker? = null
    private var locationProvider: FusedLocationProviderClient? = null

    private val viewModel: MainViewModel by navGraphViewModels(R.id.nav_graph_xml)
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private var centered = true
    private var sensorAccuracy = 0

    private lateinit var map: GoogleMap
    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        infoWindowAdapter = MyInfoWindowAdapter(InfoWindowBinding.inflate(inflater, container, false), viewModel)
        sensorManager = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        locationProvider = LocationServices.getFusedLocationProviderClient(requireContext())
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                mapFragment.getMapAsync(this)
            } else {
                Utils.toast("no")
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
                mapFragment.getMapAsync(this)
            }
        }
        createFab = binding.mapAddFab
        createFab.setOnClickListener {
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
        createFab.setOnLongClickListener { //TODO debug only
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val degreeToNorth = Math.toDegrees((orientationAngles[0].toDouble())) //Angle to true north from device
            FileLog.d("O_O", "degrees: $degreeToNorth")
            true
        }
        routeFab = binding.mapRouteFab
        routeFab.setOnClickListener {
            viewModel.updateCurrentPlace(selectedMarker!!)
            viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_NAV
            findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
        }
        searchInArFab = binding.mapArSearchFab
        searchInArFab.setOnClickListener {
            // viewModel.updateCurrentPlace(selectedMarker!!) potentially highlight marker in AR
            viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_SEARCH
            findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
        }
        myLocationFab = binding.mapMyLocationFab
        myLocationFab.setImageResource(R.drawable.ic_baseline_gps_not_fixed_24)
        myLocationFab.setOnClickListener { zoomOnMyLocation() }
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
    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Add a marker in Sydney and move the camera
        map.setInfoWindowAdapter(infoWindowAdapter)

        viewModel.places.observe(viewLifecycleOwner) {
            routeFab.visibility = View.GONE
            createFab.setImageResource(R.drawable.ic_baseline_add_location_alt_24)
            map.clear()
            selectedMarker = null
            viewModel.placesMap.clear()
            for (place in it) {
                val marker = map.addMarker(MarkerOptions().position(LatLng(place.lat, place.lng)))
                viewModel.placesMap[marker!!] = place
            }
        }
        viewModel.fetchPlaces()
        map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(52.0, 13.0)))

        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.isMyLocationEnabled = true
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        map.setOnCameraMoveStartedListener {
            if (centered) {
                centered = false
            } else {
                myLocationFab.setImageResource(R.drawable.ic_baseline_gps_not_fixed_24)
            }
        }
        map.setOnMarkerClickListener { marker ->
            if (marker.isInfoWindowShown) {
                marker.hideInfoWindow()
            } else {
                selectedMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                selectedMarker = marker
                marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                marker.showInfoWindow()
                routeFab.visibility = View.VISIBLE
                createFab.setImageResource(R.drawable.ic_baseline_edit_location_alt_24)
            }
            true
        }
        map.setOnInfoWindowClickListener { marker ->
            viewModel.updateCurrentPlace(selectedMarker!!)
            viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_NAV
            findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
        }
        map.setOnInfoWindowLongClickListener { marker ->
            viewModel.updateCurrentPlace(marker)
            viewModel.navState = MainViewModel.NavState.MAPS_TO_EDIT
            findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToCreateFragment())
        }
        map.setOnMapClickListener {
            selectedMarker?.let {
                it.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                selectedMarker = null
                routeFab.visibility = View.GONE
                createFab.setImageResource(R.drawable.ic_baseline_add_location_alt_24)
            }
        }
        zoomOnMyLocation()

        myLocationFab.setOnLongClickListener {
            if (map.mapType == GoogleMap.MAP_TYPE_NORMAL) {
                map.mapType = GoogleMap.MAP_TYPE_SATELLITE
            } else {
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
            }
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        map.clear()
        _binding = null
        selectedMarker = null
    }

    override fun onResume() {
        super.onResume()

        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(
                this,
                magneticField,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(it.values, 0, accelerometerReading, 0, accelerometerReading.size)
            } else if (it.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(it.values, 0, magnetometerReading, 0, magnetometerReading.size)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {
        sensorAccuracy = acc
        binding.mapsSensorAccuracyReading.text = sensorAccuracy.toString()
    }

    private fun startNavigationIntentToMarker(marker: Marker) {
        val coords = "" + marker.position.latitude + ", " + marker.position.longitude
        val intentUri = Uri.parse("google.navigation:q=$coords&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, intentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        startActivity(mapIntent)
    }

    @SuppressLint("MissingPermission")
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
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
                    myLocationFab.setImageResource(R.drawable.ic_baseline_gps_fixed_24)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onRouteClick(view: View) {
        locationProvider?.let {
            it.lastLocation.addOnSuccessListener { location ->
                selectedMarker?.let { marker ->
                    val markerLocation = Location("")
                    markerLocation.latitude = marker.position.latitude
                    markerLocation.longitude = marker.position.longitude
                    val distance = location.distanceTo(markerLocation)
                    val bearingToAnchor = location.bearingTo(markerLocation)
                    if (distance >= MAX_DISTANCE_TO_START) {
                        startNavigationIntentToMarker(selectedMarker!!)
                    } else {
                        onLocationConfirmationDialog(view, distance)
                    }
                }
            }
        }
    }

    private fun onLocationConfirmationDialog(view: View, distance: Float) {

        val builder = AlertDialog.Builder(view.context)
        builder.setTitle(getString(R.string.route_dialog_title))
        builder.setMessage("You already are only $distance meters away from the starting position, ready to start AR-Navigation? If not use Google Maps to get closer")
        builder.setPositiveButton("Start AR") { _, _ ->
            //calculate direction and distance from gps to marker
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val degreeToNorth = Math.toDegrees((orientationAngles[0].toDouble())) //Angle to true north from device

            viewModel.navState = MainViewModel.NavState.MAPS_TO_AR_NAV
            findNavController().navigate(MapsFragmentDirections.actionMapsFragmentToArFragment())
        }
        builder.setNegativeButton("Google Maps") { _, _ ->
            viewModel.clearCurrentPlace()
            startNavigationIntentToMarker(selectedMarker!!)
        }
        builder.setNeutralButton("Cancel") { _, _ ->
        }

        builder.show()
    }
}