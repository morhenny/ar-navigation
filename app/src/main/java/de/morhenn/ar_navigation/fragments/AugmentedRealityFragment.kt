package de.morhenn.ar_navigation.fragments

import android.net.Uri.parse
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.*
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.rendering.ResourceManager
import com.google.ar.sceneform.rendering.ViewRenderable
import de.morhenn.ar_navigation.AnchorHostingPoint
import de.morhenn.ar_navigation.MainViewModel
import de.morhenn.ar_navigation.adapter.MyListAdapter
import de.morhenn.ar_navigation.R
import de.morhenn.ar_navigation.fragments.AugmentedRealityFragment.ModelName.*
import de.morhenn.ar_navigation.databinding.FragmentAugmentedRealityBinding
import de.morhenn.ar_navigation.model.ArPoint
import de.morhenn.ar_navigation.model.ArRoute
import de.morhenn.ar_navigation.persistance.Place
import de.morhenn.ar_navigation.util.FileLog
import de.morhenn.ar_navigation.util.GeoUtils
import de.morhenn.ar_navigation.util.Utils
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.Filament
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.arcore.ArSession
import io.github.sceneview.ar.arcore.planeFindingEnabled
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.arcore.yDirection
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.ArNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.math.*
import io.github.sceneview.model.await
import io.github.sceneview.node.ViewNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.math.atan2


class AugmentedRealityFragment : Fragment() {
    companion object {
        private const val TAG = "AR-Frag"
        private const val RENDER_DISTANCE = 200f //default is 30
        private const val H_ACC_0 = 0.0
        private const val H_ACC_1 = 10
        private const val H_ACC_2 = 2.5
        private const val H_ACC_3 = 1.5
        private const val H_ACC_4 = 1.0
        private const val V_ACC_0 = 0.0
        private const val V_ACC_1 = 2.0
        private const val V_ACC_2 = 1.5
        private const val V_ACC_3 = 1.0
        private const val V_ACC_4 = 0.75
        private const val HEAD_ACC_0 = 0.0
        private const val HEAD_ACC_1 = 20.0
        private const val HEAD_ACC_2 = 10.0
        private const val HEAD_ACC_3 = 5.0
        private const val HEAD_ACC_4 = 2.5
        private const val IGNORE_GEO_ACC = true
        private const val SEARCH_RADIUS = 200.0
        private const val MAX_RESOLVE_DISTANCE = 10f
        private const val RENDER_PREVIEW_ARROW_IN_SEARCH_DISTANCE = 4f
        private const val DISTANCE_TO_UPDATE_FETCHED_PLACES = 2f
    }

    enum class AppState {
        STARTING_AR, //"Searching surfaces"
        PLACE_ANCHOR,
        WAITING_FOR_ANCHOR_CIRCLE,
        HOSTING, //either go to HOST_SUCCESS or back to PLACE_ANCHOR
        HOST_SUCCESS,
        HOST_FAIL,
        PLACE_OBJECT,
        PLACE_TARGET,
        TARGET_PLACED,
        RESOLVE_ABLE,
        RESOLVE_BUT_NOT_READY,
        RESOLVING,
        RESOLVE_SUCCESS,
        RESOLVE_FAIL, //difficult to be sure - most likely after some timeout
        SEARCHING,
    }

    enum class ModelName {
        ARROW_FORWARD,
        ARROW_LEFT,
        ARROW_RIGHT,
        CUBE,
        ANCHOR,
        ANCHOR_PREVIEW,
        ANCHOR_PREVIEW_ARROW,
        ANCHOR_SEARCH_ARROW,
        TARGET,
    }

    enum class Indicator {
        RIGHT,
        LEFT,
        NONE,
    }

    enum class FabState {
        PLACE,
        CONFIRM,
        RESOLVE,
        HOST,
    }

    private var _binding: FragmentAugmentedRealityBinding? = null
    private val binding get() = _binding!!

    private lateinit var sceneView: ArSceneView
    private var anchorNode: ArModelNode? = null
    private var placementNode: ArModelNode? = null
    private lateinit var anchorCircle: AnchorHostingPoint

    private var nodeList: MutableList<ArNode> = ArrayList()
    private var pointList: MutableList<ArPoint> = ArrayList()
    private val adapter = MyListAdapter(pointList)

    private var modelMap: EnumMap<ModelName, Renderable> = EnumMap(ModelName::class.java)
    private var cloudAnchor: Anchor? = null
    private var cloudAnchorId: String? = ""
    private var arRoute: ArRoute? = null
    private var navOnly = false
    private var startRotation = 0f
    private var scale = 1.5f
    private var isTracking = false
    private var placedNew = false
    private var isShowingTooFarInfo = false

    private var isSearchingMode = false
    private var observing: Boolean = false
    private var firstSearchFetched = false
    private var resolvedFromSearchMode = false
    private var lastSearchLatLng: LatLng = LatLng(0.0, 0.0)
    private val placesInRadiusNodeMap = HashMap<Place, ArNode>()
    private val placesInRadiusPreviewArrowMap = HashMap<Place, ArNode>()
    private val placesInRadiusInfoNodes = ArrayList<ViewNode>()

    private var geoLat = 0.0
    private var geoLng = 0.0
    private var geoAlt = 0.0
    private var geoHdg = 0.0

    private var earthAnchorPlaced = false
    private var earth: Earth? = null
    private var earthNode: ArNode? = null
    private var previewArrow: ArNode? = null

    private var appState: AppState = AppState.STARTING_AR
    private var selectedModel: ModelName = ANCHOR

    private val viewModel: MainViewModel by navGraphViewModels(R.id.nav_graph_xml)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAugmentedRealityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launchWhenCreated {
            loadModels()
        }

        sceneView = binding.sceneView
        sceneView.cameraDistance = RENDER_DISTANCE
        sceneView.configureSession { _: ArSession, config: Config ->
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.geospatialMode = Config.GeospatialMode.ENABLED
            config.planeFindingEnabled = true
        }
        sceneView.planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_TOP_MOST
        sceneView.planeRenderer.isShadowReceiver = false
        sceneView.camera.farClipPlane = RENDER_DISTANCE

        sceneView.onArSessionCreated = {
            FileLog.d(TAG, "Session is created: $it")
        }
        sceneView.onArSessionFailed = {
            FileLog.e(TAG, "Session failed with exception: $it")
        }

        sceneView.lifecycle.addObserver(onArFrame = { arFrame ->

            if (arFrame.isTrackingPlane && !isTracking) {
                isTracking = true
                binding.arExtendedFab.isEnabled = true
                if (!navOnly && !isSearchingMode) {
                    anchorCircle = AnchorHostingPoint(requireContext(), Filament.engine, sceneView.renderer.filamentScene)
                    anchorCircle.enabled = true
                    placementNode = ArModelNode(placementMode = PlacementMode.PLANE_HORIZONTAL).apply {
                        parent = sceneView
                        isVisible = true
                    }
                }
            }
            if (appState == AppState.PLACE_ANCHOR) {
                placementNode?.let {
                    it.pose?.let { pose ->
                        anchorCircle.setPosition(pose)
                    }
                }
            } else if (appState == AppState.WAITING_FOR_ANCHOR_CIRCLE) {
                if (anchorCircle.isInFrame(arFrame.camera)) {
                    anchorCircle.highlightSegment(arFrame.camera.pose)
                }
                if (anchorCircle.allSegmentsHighlighted && isTracking) {
                    onHost()
                }
            }

            earth?.let {
                if (it.trackingState == TrackingState.TRACKING) {
                    earthIsTrackingLoop(it)
                }
            } ?: run {
                earth = sceneView.arSession?.earth
                FileLog.d(TAG, "Earth object assigned")
            }
        })

        initUI()

        when (viewModel.navState) {
            MainViewModel.NavState.MAPS_TO_AR_NAV -> {
                sceneView.instructions.enabled = false
                navOnly = true
                updateState(AppState.RESOLVE_ABLE)
            }
            MainViewModel.NavState.MAPS_TO_AR_SEARCH -> {
                isSearchingMode = true
                updateState(AppState.SEARCHING)
            }
            MainViewModel.NavState.MAPS_TO_AR_NEW -> {
                navOnly = false
                updateState(AppState.PLACE_ANCHOR)
            }
            MainViewModel.NavState.CREATE_TO_AR_TO_TRY -> {
                sceneView.instructions.enabled = false
                navOnly = true
                updateState(AppState.RESOLVE_ABLE)
            }
            else -> {
                throw IllegalStateException("Invalid NavState in AugmentedRealityFragment: ${viewModel.navState}")
            }
        }
    }

    private fun earthIsTrackingLoop(earth: Earth) {
        val cameraGeospatialPose = earth.cameraGeospatialPose

        //update UI element for horizontal accuracy
        binding.arVpsAccuracy.visibility = View.VISIBLE
        binding.viewAccHorizontalRaw.text = String.format("%.2fm", cameraGeospatialPose.horizontalAccuracy)
        binding.viewAccHorizontal0.visibility = if (cameraGeospatialPose.horizontalAccuracy < H_ACC_0) View.INVISIBLE else View.VISIBLE
        binding.viewAccHorizontal1.visibility = if (cameraGeospatialPose.horizontalAccuracy > H_ACC_1) View.INVISIBLE else View.VISIBLE
        binding.viewAccHorizontal2.visibility = if (cameraGeospatialPose.horizontalAccuracy > H_ACC_2) View.INVISIBLE else View.VISIBLE
        binding.viewAccHorizontal3.visibility = if (cameraGeospatialPose.horizontalAccuracy > H_ACC_3) View.INVISIBLE else View.VISIBLE
        binding.viewAccHorizontal4.visibility = if (cameraGeospatialPose.horizontalAccuracy > H_ACC_4) View.INVISIBLE else View.VISIBLE

        //update UI element for vertical accuracy
        val altText = String.format("%.2fm", cameraGeospatialPose.verticalAccuracy) + String.format(" %.2fm", cameraGeospatialPose.altitude)
        binding.viewAccVerticalRaw.text = altText
        binding.viewAccVertical0.visibility = if (cameraGeospatialPose.verticalAccuracy < V_ACC_0) View.INVISIBLE else View.VISIBLE
        binding.viewAccVertical1.visibility = if (cameraGeospatialPose.verticalAccuracy > V_ACC_1) View.INVISIBLE else View.VISIBLE
        binding.viewAccVertical2.visibility = if (cameraGeospatialPose.verticalAccuracy > V_ACC_2) View.INVISIBLE else View.VISIBLE
        binding.viewAccVertical3.visibility = if (cameraGeospatialPose.verticalAccuracy > V_ACC_3) View.INVISIBLE else View.VISIBLE
        binding.viewAccVertical4.visibility = if (cameraGeospatialPose.verticalAccuracy > V_ACC_4) View.INVISIBLE else View.VISIBLE

        //update UI element for heading accuracy
        binding.viewAccHeadingRaw.text = String.format("%.2f°", cameraGeospatialPose.headingAccuracy)
        binding.viewAccHeading0.visibility = if (cameraGeospatialPose.headingAccuracy < HEAD_ACC_0) View.INVISIBLE else View.VISIBLE
        binding.viewAccHeading1.visibility = if (cameraGeospatialPose.headingAccuracy > HEAD_ACC_1) View.INVISIBLE else View.VISIBLE
        binding.viewAccHeading2.visibility = if (cameraGeospatialPose.headingAccuracy > HEAD_ACC_2) View.INVISIBLE else View.VISIBLE
        binding.viewAccHeading3.visibility = if (cameraGeospatialPose.headingAccuracy > HEAD_ACC_3) View.INVISIBLE else View.VISIBLE
        binding.viewAccHeading4.visibility = if (cameraGeospatialPose.headingAccuracy > HEAD_ACC_4) View.INVISIBLE else View.VISIBLE

        if (isSearchingMode && cameraGeospatialPose.horizontalAccuracy < 2) {

            val cameraLatLng = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)

            placesInRadiusNodeMap.keys.forEach { place ->
                placesInRadiusPreviewArrowMap[place]?.let { previewNode ->
                    placesInRadiusNodeMap[place]?.let { earthNode ->
                        val distance = GeoUtils.distanceBetweenTwo3dCoordinates(earthNode.worldPosition, sceneView.camera.worldPosition)
                        previewNode.isVisible = distance > RENDER_PREVIEW_ARROW_IN_SEARCH_DISTANCE
                    }
                }
            }

            val distanceBetween = GeoUtils.distanceBetweenTwoCoordinates(lastSearchLatLng, cameraLatLng)
            if (distanceBetween > DISTANCE_TO_UPDATE_FETCHED_PLACES) {
                lifecycleScope.launch {
                    viewModel.fetchPlacesAroundLocation(cameraLatLng, SEARCH_RADIUS)
                }
                firstSearchFetched = true
                lastSearchLatLng = cameraLatLng
            }

            //Start observing search results
            observeAround()

        } else if (!earthAnchorPlaced && (viewModel.navState == MainViewModel.NavState.MAPS_TO_AR_NAV || viewModel.navState == MainViewModel.NavState.CREATE_TO_AR_TO_TRY)) {
            //Prediction of the cloud anchor location in NAV mode
            viewModel.currentPlace?.let {
                if (cameraGeospatialPose.horizontalAccuracy < H_ACC_2) {
                    val earthAnchor = earth.createAnchor(it.lat, it.lng, it.alt, 0f, 0f, 0f, 1f)
                    earthAnchorPlaced = true
                    earthNode = ArNode().apply {
                        anchor = earthAnchor
                        setModel(modelMap[ANCHOR_PREVIEW])
                        parent = sceneView
                    }
                    previewArrow = ArNode().also { arrow ->
                        arrow.position = Position(0f, 2f, 0f)
                        arrow.parent = earthNode
                        arrow.setModel(modelMap[ANCHOR_PREVIEW_ARROW])
                    }
                }
            }
        } else if (earthAnchorPlaced && cloudAnchor == null) {
            earthNode?.let {
                indicateDirectionIfNotInView(it)
            }
        }
    }

    //Observing the LiveData of places around the current location, when in searchMode
    private fun observeAround() {
        if (firstSearchFetched) {
            if (!observing) {
                observing = true
                viewModel.placesInRadius.observe(viewLifecycleOwner) {
                    renderObservedPlaces(it)
                }
            } else if (placesInRadiusInfoNodes.isNotEmpty()) {
                placesInRadiusInfoNodes.forEach {
                    with(it.parent as ArNode) {
                        val cameraLocalPosition = worldToLocalPosition(sceneView.camera.position.toVector3()).toFloat3()
                        val newQuaternion = lookAt(cameraLocalPosition, it.position, Direction(y = 1.0f)).toQuaternion()
                        it.transform(quaternion = newQuaternion)
                    }
                }
            }
        }
    }

    private fun renderObservedPlaces(places: List<Place>) {
        earth?.let { earth ->
            for (place in places) {
                placesInRadiusNodeMap[place]?.let {
                    //NO-OP
                } ?: run {
                    val tempEarthAnchor = earth.createAnchor(place.lat, place.lng, place.alt, 0f, 0f, 0f, 1f)

                    val tempEarthNode = ArNode().apply {
                        anchor = tempEarthAnchor
                        setModel(modelMap[ANCHOR_PREVIEW])
                        parent = sceneView
                    }

                    val tempPreviewArrow = ArNode().apply {
                        position = Position(0f, 2f, 0f)
                        setModel(modelMap[ANCHOR_SEARCH_ARROW])
                        parent = tempEarthNode
                        isVisible = GeoUtils.distanceBetweenTwo3dCoordinates(tempEarthNode.worldPosition, sceneView.camera.worldPosition) > RENDER_PREVIEW_ARROW_IN_SEARCH_DISTANCE
                    }

                    val tempInfoNode = ViewNode().apply {
                        position = Position(0f, 1f, 0f)
                        parent = tempEarthNode
                    }
                    lifecycleScope.launch {
                        val infoRenderable = ViewRenderable.builder()
                            .setView(requireContext(), R.layout.ar_place_info)
                            .build(lifecycle)
                        infoRenderable.whenComplete { viewRenderable, _ ->
                            tempInfoNode.setRenderable(viewRenderable)
                            viewRenderable.view.findViewById<TextView>(R.id.info_name).text = place.name
                        }
                    }
                    placesInRadiusInfoNodes.add(tempInfoNode)
                    placesInRadiusNodeMap[place] = tempEarthNode
                    placesInRadiusPreviewArrowMap[place] = tempPreviewArrow
                }
            }
        }
    }

    private fun arToJsonString(): String {
        var result = ""
        cloudAnchorId?.let { id ->
            result = Json.encodeToString(ArRoute(id, pointList))
        } ?: Utils.toast("ERROR parsing to JSON - No Cloud Anchor ID")
        FileLog.d("TAG", "Convert ArData to Json String: $result")
        return result
    }

    private fun jsonToAr(json: String): ArRoute? {
        if (arRoute == null) {
            try {
                arRoute = Json.decodeFromString<ArRoute>(json)
            } catch (e: Exception) {
                FileLog.e("TAG", "ArData could not be parsed, wrong JSON format - $json")
                Utils.toast("ArData could not be parsed, wrong JSON format")
            }
        }
        FileLog.d("TAG", "Resolved an ArRoute from json: ${arRoute.toString()}")
        return arRoute
    }

    private fun cloudAnchor(newAnchor: Anchor?) {
        cloudAnchor?.detach()
        cloudAnchor = newAnchor
    }

    private fun onPlace() {
        if (appState == AppState.HOST_FAIL) {
            appState = AppState.PLACE_ANCHOR
        }
        placementNode?.let { pNode ->
            when (appState) {
                AppState.PLACE_ANCHOR -> {
                    sceneView.arSession?.earth?.let { earth ->
                        if (earth.trackingState == TrackingState.TRACKING) {
                            val cameraGeospatialPose = earth.cameraGeospatialPose
                            if (IGNORE_GEO_ACC || cameraGeospatialPose.horizontalAccuracy < H_ACC_2) {
                                updateState(AppState.WAITING_FOR_ANCHOR_CIRCLE)
                                anchorNode = ArModelNode(PlacementMode.DISABLED).apply {
                                    parent = sceneView
                                    anchor = pNode.createAnchor()
                                    isVisible = false
                                    setModel(modelMap[ANCHOR])
                                }
                                startRotation = sceneView.camera.transform.rotation.y
                                calculateLatLongOfPlacementNode(cameraGeospatialPose)
                            }
                        }
                    }
                }
                AppState.PLACE_OBJECT -> {
                    anchorNode?.let { anchorNode ->
                        ArModelNode(PlacementMode.DISABLED).apply {
                            val angle = startRotation - sceneView.camera.transform.rotation.y
                            val rotationMatrix = rotation(axis = anchorNode.pose!!.yDirection, angle = angle) //Rotation around the Y-Axis of the anchorPlane

                            parent = anchorNode
                            position = anchorNode.worldToLocalPosition(pNode.worldPosition.toVector3()).toFloat3()
                            quaternion = rotationMatrix.toQuaternion()
                            setModel(modelMap[selectedModel])
                            modelScale = Scale(this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale)
                            updateState(AppState.PLACE_OBJECT)
                            addNode(this)
                        }
                    }
                }
                AppState.PLACE_TARGET -> {
                    anchorNode?.let { anchorNode ->
                        ArModelNode(PlacementMode.DISABLED).apply {
                            val angle = startRotation - sceneView.camera.transform.rotation.y
                            val rotationMatrix = rotation(axis = anchorNode.pose!!.yDirection, angle = angle) //Rotation around the Y-Axis of the anchorPlane

                            parent = anchorNode
                            position = anchorNode.worldToLocalPosition(pNode.worldPosition.toVector3()).toFloat3()
                            quaternion = rotationMatrix.toQuaternion()
                            setModel(modelMap[TARGET])
                            modelScale = Scale(this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale)
                            updateState(AppState.TARGET_PLACED)
                            addNode(this)
                        }
                    }
                }
                else -> FileLog.e(TAG, "Invalid state when trying to place object")
            }
        } ?: FileLog.e(TAG, "No placement node available, but onPlace pressed")
    }

    private fun onHost() {
        binding.arProgressBar.visibility = View.VISIBLE
        updateState(AppState.HOSTING)
        anchorNode?.let { anchorNode ->
            anchorNode.hostCloudAnchor(365) { anchor: Anchor, success: Boolean ->
                cloudAnchor(anchor)
                binding.arProgressBar.visibility = View.GONE
                if (success) {
                    cloudAnchorId = anchor.cloudAnchorId
                    updateState(AppState.HOST_SUCCESS)
                    binding.arFabLayout.visibility = View.VISIBLE
                    anchorNode.isVisible = true
                    anchorCircle.enabled = false
                } else {
                    updateState(AppState.HOST_FAIL)
                    binding.arExtendedFab.isEnabled = true
                    clear()
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            delay(12500L)
            if (appState == AppState.HOSTING) {
                cloudAnchor?.detach()
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Hosting timed out")
                    updateState(AppState.HOST_FAIL)
                    binding.arProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun onConfirm() {
        binding.arProgressBar.visibility = View.VISIBLE
        binding.arExtendedFab.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val json = arToJsonString()
            viewModel.arDataString = json
            viewModel.currentPlace?.let {
                it.ardata = json
                it.lat = geoLat
                it.lng = geoLng
                it.heading = geoHdg
                it.alt = geoAlt
            }

            when (viewModel.navState) {
                MainViewModel.NavState.MAPS_TO_AR_NEW -> {
                    viewModel.geoLat = geoLat
                    viewModel.geoLng = geoLng
                    viewModel.geoAlt = geoAlt
                    viewModel.geoHdg = geoHdg
                    withContext(Dispatchers.Main) {
                        findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToCreateFragment())
                    }
                }
                MainViewModel.NavState.MAPS_TO_EDIT -> {
                    viewModel.currentPlace?.let {
                        it.lat = geoLat
                        it.lng = geoLng
                        it.heading = geoHdg
                        it.alt = geoAlt
                        withContext(Dispatchers.Main) {
                            findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToCreateFragment())
                        }
                    } ?: FileLog.e(TAG, "NavState is edit, but currentPlace is null in ARFragment ")
                }
                else -> {
                    binding.arProgressBar.visibility = View.GONE
                    binding.arExtendedFab.isEnabled = true
                    FileLog.e(TAG, "Wrong NavState onClick Confirm is ${viewModel.navState}")
                }
            }
        }
    }

    private fun onResolve() {
        if (navOnly) {
            if (isTracking) {
                cloudAnchor?.detach()
                val arRoute = jsonToAr(viewModel.arDataString)
                resolveRoute(arRoute)
            } else {
                updateState(AppState.RESOLVE_BUT_NOT_READY)
            }
        } else if (isSearchingMode) {
            if (placesInRadiusNodeMap.isEmpty()) {
                binding.arInfoText.text = "No places found around you... \nUse the map to navigate closer!"
                FileLog.d("TAG", "Trying to resolve inSearchingMode, but no places around.")
            } else {
                var shortestDistance = Float.MAX_VALUE
                var closestNode: Pair<Place, ArNode>? = null

                //resolve closest node that is in view
                placesInRadiusNodeMap.entries.forEach {
                    val distance = GeoUtils.distanceBetweenTwo3dCoordinates(it.value.worldPosition, sceneView.camera.worldPosition)
                    if (distance < shortestDistance && isNodeInView(it.value) && distance < MAX_RESOLVE_DISTANCE) {
                        shortestDistance = distance
                        closestNode = Pair(it.key, it.value)
                    }
                }
                closestNode?.let {
                    FileLog.d(TAG, "Trying to resolve closest Place: ${it.first.name} ...")
                    val arRoute = jsonToAr(it.first.ardata)
                    resolvedFromSearchMode = true
                    binding.arButtonUndo.visibility = View.VISIBLE
                    resolveRoute(arRoute)
                    binding.arInfoText.text = binding.arInfoText.text.toString() + "\n\nResolving: ${it.first.name}"
                }
            }
        } else {
            throw IllegalStateException("Button Resolve should only be visible in navOnly mode")
        }
    }

    private fun resolveRoute(arRoute: ArRoute?) {
        arRoute?.let { route ->
            updateState(AppState.RESOLVING)
            binding.arProgressBar.visibility = View.VISIBLE
            anchorNode = ArModelNode().also { anchorNode ->
                anchorNode.position = Position(0f, 0f, 0f)
                anchorNode.parent = sceneView
                anchorNode.resolveCloudAnchor(route.cloudAnchorId) { anchor: Anchor, success: Boolean ->
                    cloudAnchor(anchor)
                    binding.arProgressBar.visibility = View.GONE
                    binding.arIndicatorRight.visibility = View.GONE
                    binding.arIndicatorLeft.visibility = View.GONE
                    if (success) {
                        FileLog.d(TAG, "Successfully resolved route with id: ${route.cloudAnchorId}")

                        updateState(AppState.RESOLVE_SUCCESS)
                        isSearchingMode = false
                        clearSearchingModels()

                        anchorNode.setModel(modelMap[ANCHOR])
                        anchorNode.anchor = anchor
                        anchorNode.isVisible = true

                        route.pointsList.forEach {
                            ArModelNode(PlacementMode.DISABLED).apply {
                                parent = anchorNode //Set the anchor to the cloudAnchor
                                val pos = anchorNode.localToWorldPosition(it.position.toVector3())
                                position = Position(pos.x, pos.y, pos.z)
                                scale = Scale(it.scale, it.scale, it.scale)
                                rotation = it.rotation
                                setModel(modelMap[it.modelName])
                                addNode(this)
                                placedNew = true
                            }
                        }
                    } else {
                        updateState(AppState.RESOLVE_FAIL)
                        FileLog.d(TAG, "Failed resolving route with id: ${route.cloudAnchorId}")
                    }
                }
            }
        }
        binding.arExtendedFab.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            delay(12500L)
            if (appState == AppState.RESOLVING) {
                cloudAnchor?.detach()
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Resolve timed out")
                    updateState(AppState.RESOLVE_FAIL)
                    binding.arProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun isNodeInView(node: ArNode): Boolean {
        val nodeTranslation = nodeOnCameraProjection(node)

        val ndcX = nodeTranslation[0] / nodeTranslation[3]
        val ndcY = nodeTranslation[1] / nodeTranslation[3]
        val ndcZ = nodeTranslation[2] / nodeTranslation[3]

        return !(ndcX < -1 || ndcX > 1 || ndcY < -1 || ndcY > 1 || ndcZ > 1)
    }

    private fun indicateDirectionIfNotInView(node: ArNode) {
        val nodeTranslation = nodeOnCameraProjection(node)

        val ndcX = nodeTranslation[0] / nodeTranslation[3] //left-right
        val ndcY = nodeTranslation[1] / nodeTranslation[3] //up-down
        val ndcZ = nodeTranslation[2] / nodeTranslation[3] //front-back

        val distance = GeoUtils.distanceBetweenTwo3dCoordinates(node.worldPosition, sceneView.camera.worldPosition)

        if (distance < RENDER_DISTANCE) {
            if (ndcZ > 1) { //node is behind the camera
                if (ndcX > 0) {
                    //node is behind-left from the camera
                    showDirectionIndicator(Indicator.LEFT)
                } else {
                    //node is behind-right from the camera
                    showDirectionIndicator(Indicator.RIGHT)
                }
            } else { //node is in front of the camera
                if (ndcX > 1) {
                    showDirectionIndicator(Indicator.RIGHT)
                } else if (ndcX < -1) {
                    showDirectionIndicator(Indicator.LEFT)
                } else {
                    showDirectionIndicator(Indicator.NONE)
                }
            }
        } else {
            showDirectionIndicator(Indicator.NONE)
            if (!isShowingTooFarInfo) {
                binding.arInfoText.text = binding.arInfoText.text.toString() + "\n \n You are too far away from the place!"
                isShowingTooFarInfo = true
            }
        }
    }

    private fun showDirectionIndicator(direction: Indicator) {
        when (direction) {
            Indicator.LEFT -> {
                binding.arIndicatorLeft.visibility = View.VISIBLE
                binding.arIndicatorRight.visibility = View.INVISIBLE
            }
            Indicator.RIGHT -> {
                binding.arIndicatorLeft.visibility = View.INVISIBLE
                binding.arIndicatorRight.visibility = View.VISIBLE
            }
            Indicator.NONE -> {
                binding.arIndicatorLeft.visibility = View.INVISIBLE
                binding.arIndicatorRight.visibility = View.INVISIBLE
            }
        }
    }

    private fun nodeOnCameraProjection(node: ArNode): FloatArray {
        sceneView.currentFrame?.camera?.let { camera ->
            val viewProjectionMatrix = FloatArray(16)
            camera.getProjectionMatrix(viewProjectionMatrix, 0, 0.01f, 30f)

            val viewMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)

            val matrix = FloatArray(16)
            Matrix.translateM(matrix, 0, node.position.x, node.position.y, node.position.z)

            Matrix.multiplyMM(matrix, 0, viewProjectionMatrix, 0, viewMatrix, 0)

            val nodeTranslation = FloatArray(4)
            nodeTranslation[0] = node.position.x
            nodeTranslation[1] = node.position.y
            nodeTranslation[2] = node.position.z
            nodeTranslation[3] = 1f

            val nodeTranslationNDC = FloatArray(4)
            Matrix.multiplyMV(nodeTranslationNDC, 0, matrix, 0, nodeTranslation, 0)
            return nodeTranslationNDC
        } ?: run {
            FileLog.e(TAG, "No camera available for isInView check")
            return FloatArray(4)
        }
    }

    private fun calculateLatLongOfPlacementNode(cameraGeospatialPose: GeospatialPose) {
        placementNode?.let { node ->
            node.pose?.let { pose ->
                val vectorUp = pose.yDirection

                val cameraTransform = sceneView.camera.transform
                val cameraPos = cameraTransform.position
                val cameraOnPlane = cameraPos.minus(vectorUp.times((cameraPos.minus(node.position)).times(vectorUp)))
                val distanceOfCameraToGround = (cameraPos.minus(cameraOnPlane)).toVector3().length()

                //Angle between forward and the placementNode should always be ZERO

                val bearingToHit = cameraGeospatialPose.heading
                val distanceToHit = (node.position.minus(cameraOnPlane)).toVector3().length()

                val distanceToHitInKm = distanceToHit / 1000

                val latLng = GeoUtils.getLatLngByDistanceAndBearing(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude, bearingToHit, distanceToHitInKm.toDouble())
                geoLat = latLng.latitude
                geoLng = latLng.longitude
                geoAlt = cameraGeospatialPose.altitude - distanceOfCameraToGround
                geoHdg = bearingToHit
                (requireActivity().getString(
                    R.string.geospatial_pose,
                    cameraGeospatialPose.latitude,
                    cameraGeospatialPose.longitude,
                    cameraGeospatialPose.horizontalAccuracy,
                    cameraGeospatialPose.altitude,
                    cameraGeospatialPose.verticalAccuracy,
                    cameraGeospatialPose.heading,
                    cameraGeospatialPose.headingAccuracy
                ) + requireActivity().getString(
                    R.string.geospatial_anchor,
                    geoLat,
                    geoLng,
                    geoAlt,
                    geoHdg,
                    distanceToHit.toDouble()
                )).also {
                    FileLog.d(TAG, "PlacementNode location calculated: \n$it")
                }
            }
        }
    }

    private fun calculateLatLongOfHitTest(hitResult: HitResult, cameraGeospatialPose: GeospatialPose) {

        val hitNormal = hitResult.hitPose.yDirection //normal vector of the plane going up: n

        val cameraTransform = sceneView.camera.transform
        val cameraPos = cameraTransform.position
        val cameraOnPlane = cameraPos.minus(hitNormal.times((cameraPos.minus(hitResult.hitPose.position)).times(hitNormal)))
        val distanceOfCameraToGround = (cameraPos.minus(cameraOnPlane)).toVector3().length()

        //We need 2 normalized vectors as direction to calculate angle between camera-forward and hit-test
        val cameraForwardVector = cameraPos.minus((cameraTransform.forward)) //Vector on the Z axis of the phone
        val projectedForward = cameraForwardVector.minus(cameraOnPlane).toVector3().normalized().toFloat3() //forward vector of the camera but from the cameraProjection

        val hitDirection = hitResult.hitPose.position.minus(cameraOnPlane).toVector3().normalized().toFloat3() //vector from cameraProjection to hit and normalize

        val rotationToHit = atan2(hitDirection.z, hitDirection.x) - atan2(projectedForward.z, projectedForward.x)//The rotation from the cameraProjection towards the hitResult
        val rotationDegrees = Math.toDegrees(rotationToHit.toDouble())
        val bearingToHit = cameraGeospatialPose.heading - rotationDegrees

        val distanceToHit = (hitResult.hitPose.position.minus(cameraOnPlane)).toVector3().length()

        val distanceToHitInKm = distanceToHit / 1000

        val latLng = GeoUtils.getLatLngByDistanceAndBearing(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude, bearingToHit, distanceToHitInKm.toDouble())

        geoLat = latLng.latitude
        geoLng = latLng.longitude
        geoAlt = cameraGeospatialPose.altitude - distanceOfCameraToGround
        geoHdg = bearingToHit
        (requireActivity().getString(
            R.string.geospatial_pose,
            cameraGeospatialPose.latitude,
            cameraGeospatialPose.longitude,
            cameraGeospatialPose.horizontalAccuracy,
            cameraGeospatialPose.altitude,
            cameraGeospatialPose.verticalAccuracy,
            cameraGeospatialPose.heading,
            cameraGeospatialPose.headingAccuracy
        ) + requireActivity().getString(
            R.string.geospatial_anchor,
            geoLat,
            geoLng,
            geoAlt,
            geoHdg,
            distanceToHit.toDouble()
        )).also {
            binding.arInfoText.text = it
            FileLog.d(TAG, "Hit-Test location calculated: \n$it")
        }
    }


    private fun initUI() {
        binding.arNodeList.layoutManager = LinearLayoutManager(requireContext())
        binding.arNodeList.adapter = adapter

        binding.arFabArrowLeft.setOnClickListener {
            selectedModel = ARROW_LEFT
            updateState(AppState.PLACE_OBJECT)
            setModelIcons(R.drawable.ic_baseline_arrow_back_24)
        }
        binding.arFabArrowRight.setOnClickListener {
            selectedModel = ARROW_RIGHT
            updateState(AppState.PLACE_OBJECT)
            setModelIcons(R.drawable.ic_baseline_arrow_forward_24)

        }
        binding.arFabArrowForward.setOnClickListener {
            selectedModel = ARROW_FORWARD
            updateState(AppState.PLACE_OBJECT)
            setModelIcons(R.drawable.ic_baseline_arrow_upward_24)
        }
        binding.arFabTarget.setOnClickListener {
            selectedModel = TARGET
            updateState(AppState.PLACE_TARGET)
            setModelIcons(R.drawable.ic_baseline_emoji_flags_24)
        }
        binding.arExtendedFab.setOnClickListener {
            when (appState) {
                AppState.TARGET_PLACED -> onConfirm()
                AppState.RESOLVE_ABLE -> onResolve()
                AppState.RESOLVE_FAIL -> onResolve()
                AppState.SEARCHING -> onResolve()
                AppState.PLACE_ANCHOR -> onPlace()
                AppState.PLACE_OBJECT -> onPlace()
                AppState.PLACE_TARGET -> onPlace()
                else -> {
                    FileLog.e(TAG, "Extended fab clicked in not allowed state: $appState")
                }
            }
        }
        binding.arButtonUndo.setOnClickListener {
            if (resolvedFromSearchMode) {
                resolvedFromSearchMode = false
                anchorNode?.destroy()
                isSearchingMode = true
                nodeList.forEach {
                    it.parent = null
                }
                nodeList.clear()
                pointList.clear()
                with(binding) {
                    arButtonUndo.visibility = View.GONE
                    arExtendedFab.isEnabled = true
                }
                firstSearchFetched = false
                observing = false
                viewModel.fetchPlacesAroundLastLocation(SEARCH_RADIUS)
                updateState(AppState.SEARCHING)
            } else if (pointList.size == 0) {
                clear()
            } else if (pointList.size > 0) {
                if ((appState == AppState.PLACE_TARGET || appState == AppState.PLACE_OBJECT) && pointList.last().modelName == TARGET) {
                    updateState(AppState.TARGET_PLACED)
                } else {
                    pointList.removeLast()
                    nodeList.removeLast().let {
                        if (findModelName(it.model) == TARGET) {
                            updateState(AppState.PLACE_TARGET)
                        }
                        it.parent = null
                    }
                    adapter.notifyItemRemoved(pointList.lastIndex + 1)
                }
            }
        }
        binding.arButtonClear.setOnClickListener {
            clear()
        }
        //toggle occlusion - testing only
        binding.arInfoText.setOnLongClickListener {
            sceneView.arCameraStream.isDepthOcclusionEnabled = !sceneView.arCameraStream.isDepthOcclusionEnabled
            true
        }
        binding.arModelSizeToggle.addOnButtonCheckedListener { _, checkedId, _ ->
            when (checkedId) {
                R.id.ar_model_icon_s -> {
                    scale = 1f
                }
                R.id.ar_model_icon_m -> {
                    scale = 1.5f
                }
                R.id.ar_model_icon_l -> {
                    scale = 2f
                }
            }
            placementNode?.modelScale = Scale(scale, scale, scale)
        }
    }

    private fun setModelIcons(iconId: Int) {
        binding.arModelIconS.icon = requireActivity().getDrawable(iconId)
        binding.arModelIconM.icon = requireActivity().getDrawable(iconId)
        binding.arModelIconL.icon = requireActivity().getDrawable(iconId)
    }

    private suspend fun loadModels() {
//        //GLBLoader causes issues in 0.9.0
//        modelMap[ARROW_FORWARD] = GLBLoader.loadModel(requireContext(), lifecycle, "models/arrow_fw.glb")
//        modelMap[ARROW_LEFT] = GLBLoader.loadModel(requireContext(), lifecycle, "models/arrow_lf.glb")
//        modelMap[ARROW_RIGHT] = GLBLoader.loadModel(requireContext(), lifecycle, "models/arrow_rd.glb")
//        modelMap[CUBE] = GLBLoader.loadModel(requireContext(), lifecycle, "models/cube.glb")
//        modelMap[ANCHOR] = GLBLoader.loadModel(requireContext(), lifecycle, "models/anchor.glb")
//        modelMap[ANCHOR_PREVIEW] = GLBLoader.loadModel(requireContext(), lifecycle, "models/anchor_preview.glb")
//        modelMap[ANCHOR_PREVIEW_ARROW] = GLBLoader.loadModel(requireContext(), lifecycle, "models/preview_arrow_facing_down.glb")
//        modelMap[TARGET] = GLBLoader.loadModel(requireContext(), lifecycle, "models/target.glb")
//        modelMap[ANCHOR_SEARCH_ARROW] = GLBLoader.loadModel(requireContext(), lifecycle, "models/small_preview_arrow_blue.glb")

        modelMap[ARROW_FORWARD] = ModelRenderable.builder()
            .setSource(context, parse("models/arrow_forward.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ARROW_LEFT] = ModelRenderable.builder()
            .setSource(context, parse("models/arrow_left.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ARROW_RIGHT] = ModelRenderable.builder()
            .setSource(context, parse("models/arrow_right.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[CUBE] = ModelRenderable.builder()
            .setSource(context, parse("models/cube.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ANCHOR] = ModelRenderable.builder()
            .setSource(context, parse("models/anchor.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ANCHOR_PREVIEW] = ModelRenderable.builder()
            .setSource(context, parse("models/anchor_preview.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ANCHOR_PREVIEW_ARROW] = ModelRenderable.builder()
            .setSource(context, parse("models/preview_arrow_facing_down.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[TARGET] = ModelRenderable.builder()
            .setSource(context, parse("models/target.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ANCHOR_SEARCH_ARROW] = ModelRenderable.builder()
            .setSource(context, parse("models/small_preview_arrow_blue.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
    }

    private fun findModelName(model: Renderable?): ModelName {
        model?.let {
            modelMap.keys.forEach {
                if (model == modelMap[it]) {
                    return it
                }
            }
        }
        return CUBE
    }

    private fun addNode(node: ArModelNode) {
        nodeList.add(node)
        pointList.add(ArPoint(node.position, node.rotation, findModelName(node.model), scale))
        adapter.notifyItemInserted(adapter.itemCount)

    }

    private fun clear() {
        nodeList.forEach {
            it.parent = null
        }
        nodeList.clear()
        pointList.clear()
        adapter.notifyDataSetChanged()
        setModelIcons(R.drawable.ic_baseline_photo_size_select_large_24)
        anchorNode?.parent = null
        anchorNode = null
        cloudAnchor = null
        earthNode?.parent = null
        earthNode = null
        previewArrow?.parent = null
        previewArrow = null
        placementNode?.destroy()
        placementNode = ArModelNode(placementMode = PlacementMode.PLANE_HORIZONTAL).apply {
            parent = sceneView
            isVisible = false
        }
        anchorCircle.destroy()
        anchorCircle = AnchorHostingPoint(requireContext(), Filament.engine, sceneView.renderer.filamentScene)
        anchorCircle.enabled = true
        binding.arFabLayout.visibility = View.GONE
        binding.arModelSizeToggle.visibility = View.INVISIBLE
        binding.arButtonUndo.visibility = View.GONE
        binding.arButtonClear.visibility = View.GONE
        updateState(AppState.PLACE_ANCHOR)
    }

    private fun clearSearchingModels() {
        if (earthAnchorPlaced) {
            earthNode?.parent = null
            earthNode = null
        } else {
            placesInRadiusNodeMap.values.forEach {
                it.parent = null
                it.destroy()
            }
            placesInRadiusNodeMap.clear()
            placesInRadiusPreviewArrowMap.clear()
            placesInRadiusInfoNodes.clear()
        }
    }

    private fun updateState(state: AppState) {
        appState = state
        binding.arInfoText.text = when (appState) {
            AppState.STARTING_AR -> {
                "AR starting up and trying to gather environment information \n +" +
                        "Please move your phone around while looking at your anchor area"

            }
            AppState.PLACE_ANCHOR -> {
                updateExtendedFab(FabState.PLACE)
                with(binding) {
                    arExtendedFab.isEnabled = true
                    arButtonUndo.visibility = View.GONE
                    arButtonClear.visibility = View.GONE
                    arModelSizeToggle.visibility = View.GONE
                }
                "Scan your environment, until all 3 bars above are green \n" +
                        "This is the accuracy of your current positioning \n \n" +
                        "Once it's accurate, place the starting point of your route"
            }
            AppState.WAITING_FOR_ANCHOR_CIRCLE -> {
                with(binding) {
                    arExtendedFab.isEnabled = false
                    arButtonUndo.visibility = View.VISIBLE
                }
                "Please walk around the circle and scan every side, until it is fully green"
            }
            AppState.HOSTING -> {
                updateExtendedFab(FabState.HOST)
                "Anchor placed! \n \n" +
                        "Trying to host as cloud anchor..."
            }
            AppState.HOST_SUCCESS -> {
                updateExtendedFab(FabState.PLACE)
                with(binding) {
                    arButtonUndo.visibility = View.VISIBLE
                    arButtonClear.visibility = View.VISIBLE
                }
                placementNode?.destroy()
                placementNode = ArModelNode(placementMode = PlacementMode.PLANE_HORIZONTAL).apply {
                    parent = sceneView
                }
                "Successfully hosted as cloud anchor \n \n" +
                        "Press + to select the first model of your route"
            }
            AppState.HOST_FAIL -> {
                "Hosting the cloud anchor failed \n \n" +
                        "Please move your phone around the area more \n" +
                        "then try placing again"
            }
            AppState.PLACE_OBJECT -> {
                updateExtendedFab(FabState.PLACE)
                placementNode?.destroy()
                placementNode = ArModelNode(placementMode = PlacementMode.PLANE_HORIZONTAL).apply {
                    isVisible = false
                    parent = sceneView
                    setModel(modelMap[selectedModel])
                    modelScale = Scale(this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale)
                }
                placementNode?.isVisible = true
                binding.arExtendedFab.isEnabled = true
                binding.arModelSizeToggle.visibility = View.VISIBLE
                "Place markings to create a navigable route to your destination \n \n" +
                        "Press + to select a different model"
            }
            AppState.PLACE_TARGET -> {
                updateExtendedFab(FabState.PLACE)
                binding.arExtendedFab.isEnabled = true
                placementNode?.destroy()
                placementNode = ArModelNode(placementMode = PlacementMode.PLANE_HORIZONTAL).apply {
                    parent = sceneView
                    setModel(modelMap[selectedModel])
                    isVisible = true
                    modelScale = Scale(this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale, this@AugmentedRealityFragment.scale)
                }
                binding.arModelSizeToggle.visibility = View.VISIBLE
                "Mark the destination of your route with the target marker\n \n" +
                        "This will be the last marker to place"
            }
            AppState.TARGET_PLACED -> {
                binding.arModelSizeToggle.visibility = View.GONE
                placementNode?.isVisible = false
                updateExtendedFab(FabState.CONFIRM)
                "You have successfully created a full route\n \n" +
                        "Press confirm if it is ready"
            }
            AppState.RESOLVE_ABLE -> {
                updateExtendedFab(FabState.RESOLVE)
                binding.arButtonClear.visibility = View.GONE
                binding.arButtonUndo.visibility = View.GONE
                binding.arModelSizeToggle.visibility = View.GONE
                "Scan your environment, until all 3 bars above are green \n" +
                        "This is the accuracy of your current positioning \n \n" +
                        "Once it's accurate, the anchor location will be shown \n" +
                        "Hold the camera towards it and press resolve"
            }
            AppState.RESOLVE_BUT_NOT_READY -> {
                "The application does not have enough data yet \n \n" +
                        "Move the phone around and make sure to have the ground in view \n" +
                        "Then try resolve again"
            }
            AppState.RESOLVING -> {
                binding.arExtendedFab.isEnabled = false
                "Trying to resolve the cloud anchor... \n \n" +
                        "Please wait a couple seconds and potentially try again if it doesn't load"
            }
            AppState.RESOLVE_SUCCESS -> {
                binding.arExtendedFab.isEnabled = false
                "Successfully resolved the cloud anchor and the attached route \n \n" +
                        "Look around and follow the markings on your screen to the routes destination"
            }
            AppState.RESOLVE_FAIL -> {
                binding.arExtendedFab.isEnabled = true
                "Resolving of cloud anchor failed \n \n" +
                        "Move your phone around the anchor area and then try to resolve again"
            }
            AppState.SEARCHING -> {
                updateExtendedFab(FabState.RESOLVE)
                binding.arButtonClear.visibility = View.GONE
                binding.arButtonUndo.visibility = View.GONE
                binding.arModelSizeToggle.visibility = View.GONE
                "Scan your environment, until all 3 bars above are green \n" +
                        "This is the accuracy of your current positioning \n \n" +
                        "Once it's accurate, all routes around you are shown \n" +
                        "To resolve a route, look at it and press resolve"
            }
        }
    }

    private fun updateExtendedFab(type: FabState) {
        when (type) {
            FabState.PLACE -> {
                binding.arExtendedFab.text = getString(R.string.btn_place)
                binding.arExtendedFab.icon = requireActivity().getDrawable(R.drawable.ic_place_item_24)
            }
            FabState.CONFIRM -> {
                binding.arExtendedFab.text = getString(R.string.btn_confirm)
                binding.arExtendedFab.icon = requireActivity().getDrawable(R.drawable.ic_baseline_cloud_upload_24)
            }
            FabState.RESOLVE -> {
                binding.arExtendedFab.text = getString(R.string.btn_resolve)
                binding.arExtendedFab.icon = requireActivity().getDrawable(R.drawable.ic_baseline_cloud_download_24)
            }
            FabState.HOST -> {
                binding.arExtendedFab.text = getString(R.string.btn_hosting)
                binding.arExtendedFab.icon = requireActivity().getDrawable(R.drawable.ic_baseline_cloud_upload_24)
            }
        }
    }

    override fun onPause() {
        sceneView.onPause(this)
        super.onPause()
    }

    override fun onResume() {
        sceneView.onResume(this)
        super.onResume()
    }

    override fun onStop() {
        cloudAnchor?.detach()
        sceneView.onStop(this)
        super.onStop()
    }

    override fun onStart() {
        sceneView.onStart(this)
        super.onStart()
    }

    override fun onDestroy() {
        //TODO this is only needed in 0.6.0, since it is in sceneView for newer versions
        ResourceManager.getInstance().destroyAllResources()

        sceneView.onDestroy(this)
        _binding = null
        super.onDestroy()
    }
}
