package de.morhenn.ar_navigation

import android.net.Uri.parse
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.*
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import de.morhenn.ar_navigation.AugmentedRealityFragment.ModelName.*
import de.morhenn.ar_navigation.databinding.FragmentAugmentedRealityBinding
import de.morhenn.ar_navigation.model.ArPoint
import de.morhenn.ar_navigation.model.ArRoute
import de.morhenn.ar_navigation.persistance.Place
import de.morhenn.ar_navigation.util.FileLog
import de.morhenn.ar_navigation.util.GeoUtils
import de.morhenn.ar_navigation.util.Utils
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.Filament
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.arcore.*
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.ArNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.toFloat3
import io.github.sceneview.math.toVector3
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
        private const val RENDER_DISTANCE = 250f //default is 30
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
        private const val MIN_RESOLVE_DISTANCE = 5
    }

    enum class AppState {
        STARTING_AR, //"Searching surfaces"
        PLACE_ANCHOR,
        WAITING_FOR_ANCHOR_CIRCLE,
        HOSTING, //either go to hosted_success or back to place_anchor
        HOST_SUCCESS,
        HOST_FAIL,
        SELECT_OBJECT,
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
        AXIS,

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

    private var isSearchingMode = false
    private var observing: Boolean = false
    private var currentFrameCounter = 0
    private var firstSearchFetched = false
    private var lastSearchLatLng: LatLng = LatLng(0.0, 0.0)
    private val placesInRadiusNodeMap = HashMap<Place, ArNode>()
    private val placesInRadiusEarthAnchors = ArrayList<Anchor>()
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
        //sceneView.arCameraStream.isDepthOcclusionEnabled = true //this needs to be called after placing is complete
        sceneView.configureSession { _: ArSession, config: Config ->
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL //horizontal for now, potentially try out vertical for target later
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.geospatialMode = Config.GeospatialMode.ENABLED
            config.planeFindingEnabled = true
            config.instantPlacementEnabled = false
        }
        sceneView.planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_TOP_MOST
        sceneView.planeRenderer.isShadowReceiver = false

        sceneView.onArSessionCreated = {
            FileLog.d(TAG, "Session is created: $it")
        }
        sceneView.onArSessionFailed = {
            FileLog.e(TAG, "Session failed with exception: $it")

        }

        sceneView.lifecycle.addObserver(onArFrame = { arFrame ->

            if (arFrame.isTrackingPlane && !isTracking) {
                isTracking = true
                binding.arExtendedFab.isEnabled = true //!isSearchingMode
                if (!navOnly && !isSearchingMode) {
                    anchorCircle = AnchorHostingPoint(requireContext(), Filament.engine, sceneView.scene)
                    anchorCircle.enabled = true
                    placementNode = ArModelNode(placementMode = PlacementMode.PLANE_HORIZONTAL).apply {
                        parent = sceneView
                        isSmoothPoseEnable = true
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
                if (anchorCircle.allSegmentsHighlighted) {
                    onHost()
                }
            }

            //Only needed for Accuracy Testing
//            arFrame.updatedPlanes.forEach { plane ->
//                //Calculate distance between planes and resolved objects
//                if (placedNew) {
//                    val normalVector = plane.centerPose.yDirection //normal vector of the plane going up
//                    placedNew = false
//                    nodeList.forEach {
//                        //normalVector = it.worldToLocalPosition(normalVector.toVector3()).toFloat3()
//                        val centerPos = it.worldToLocalPosition(plane.centerPose.position.toVector3()).toFloat3()
//                        val projectedNode = it.position.minus(normalVector.times((it.position.minus(centerPos)).times(normalVector)))
//                        val distance = projectedNode.minus(it.position).toVector3().length()
//                        FileLog.d("O_O", "Distance to plane for this node: $distance")
//                    }
//                }
//            }

            earth?.let {
                if (it.trackingState == TrackingState.TRACKING) {
                    earthIsTrackingLoop(it)
                }
            } ?: run {
                earth = sceneView.arSession?.earth
                Log.d("O_O", "Earth object assigned")
            }
        })

        initUI()

        val arData = viewModel.arDataString
        if (arData.isNotBlank()) {
            sceneView.instructions.enabled = false
            navOnly = true
            updateState(AppState.RESOLVE_ABLE)
        } else if (viewModel.navState == MainViewModel.NavState.MAPS_TO_AR_SEARCH) {
            isSearchingMode = true
            updateState(AppState.SEARCHING)

        } else if (viewModel.navState == MainViewModel.NavState.MAPS_TO_AR_NEW) {
            navOnly = false
            updateState(AppState.PLACE_ANCHOR)
        }
    }

    private fun earthIsTrackingLoop(earth: Earth) {
        val cameraGeospatialPose = earth.cameraGeospatialPose
        //FileLog.d("VPS", "Earth is Tracking and cameraPose is: ${cameraGeospatialPose.latitude}, ${cameraGeospatialPose.longitude} at ${cameraGeospatialPose.altitude}")

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

            everyXthArFrame(25, LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude))
            //Start observing search results
            observeAround()


        } else if (!earthAnchorPlaced && (viewModel.navState == MainViewModel.NavState.MAPS_TO_AR_NAV || viewModel.navState == MainViewModel.NavState.CREATE_TO_AR_TO_TRY)) {
            //Prediction of the cloud anchor location in NAV mode
            viewModel.currentPlace?.let {
                if (cameraGeospatialPose.horizontalAccuracy < 2) { //TODO potentially change preview render, depending on accuracy and distance to it
                    val earthAnchor = earth.createAnchor(it.lat, it.lng, it.alt, 0f, 0f, 0f, 1f)
                    FileLog.d("O_O", "Creating the earthAnchor at ${earthAnchor.pose.position}")
                    earthAnchorPlaced = true
                    earthNode = ArNode().also { node ->
                        node.anchor = earthAnchor
                        node.setModel(modelMap[ANCHOR_PREVIEW])
                        node.parent = sceneView
                    }
                    FileLog.d("O_O", "Creating the earthNode at ${earthNode!!.position} and world: ${earthNode!!.worldPosition}")
                    previewArrow = ArNode().also { arrow ->
                        arrow.position = Position(0f, 2f, 0f)
                        arrow.parent = earthNode
                        arrow.setModel(modelMap[ANCHOR_PREVIEW_ARROW])
                    }
                }
                //FileLog.d("O_O", "Earth Node was placed at ${earthAnchor.pose.position}")
            }
        }
    }

    //Run on every @x onArFrame call
    private fun everyXthArFrame(x: Int, latLng: LatLng) {
        if (currentFrameCounter < x) {
            currentFrameCounter++
        } else {
            currentFrameCounter = 0
            //FileLog.d("O_O", "$x th frame")

            val distanceBetween = GeoUtils.distanceBetweenTwoCoordinates(lastSearchLatLng, latLng)
            if (distanceBetween > SEARCH_RADIUS / 2) {

                lifecycleScope.launch {
                    viewModel.fetchPlacesAroundLocation(latLng, SEARCH_RADIUS)
                }
                firstSearchFetched = true
                lastSearchLatLng = latLng
                FileLog.d("O_O", "Searching for places around ${latLng.latitude}, ${latLng.longitude}")
            }

//            renderObservedPlaces(placesInRadiusNodeMap.keys.toList()) //TODO this needs to happen, if the earth anchors aren't updated automatically

            //Update the rotation of the info banners TODO
//            placesInRadiusInfoNodes.forEach {
//                //FileLog.d("O_O", "Updating ${it.name} from ${it.rotation}")
//                it.lookAt(sceneView.cameraNode)
//                //FileLog.d("O_O", "To ${it.rotation}")
//            }
        }
    }

    //Observing the LiveData of places around the current location, when in searchMode
    private fun observeAround() {
        if (!observing && firstSearchFetched) {
            observing = true
            viewModel.placesInRadius.observe(viewLifecycleOwner) {
                FileLog.d("O_O", "observing places around ${it.size}")
                renderObservedPlaces(it)
            }
        }
    }

    private fun renderObservedPlaces(places: List<Place>) {
        earth?.let { earth ->
            for (place in places) {
                placesInRadiusNodeMap[place]?.let {
                    //it.position = tempEarthAnchor.pose.position
                    //TODO this might not be needed anymore ~ earth anchors should update automatically, but don't quite
                } ?: run {
                    val tempEarthAnchor = earth.createAnchor(place.lat, place.lng, place.alt, 0f, 0f, 0f, 1f)

                    val tempEarthNode = ArNode().also { node ->
                        node.anchor = tempEarthAnchor
                        node.isSmoothPoseEnable = false //TODO this is a temporary fix, that makes the nodes position be updated by the earth anchor //check in 0.9.0
                        node.setModel(modelMap[ANCHOR_PREVIEW])
                        node.parent = sceneView
                    }

                    val tempPreviewArrow = ArNode().also { arrow -> //blue small arrow above // potentially make larger
                        arrow.position = Position(0f, 2f, 0f)
                        arrow.setModel(modelMap[ANCHOR_SEARCH_ARROW])
                        arrow.parent = tempEarthNode
                    }

//                    val tempInfoNode = ViewNode().also { node ->
//                        node.position = Position(0f, 1f, 0f)
//                        //node.rotation = Rotation(0f, 0f, 0f)
//                        node.parent = tempEarthNode
//                        node.lookAt(sceneView.cameraNode) //TODO lookat doesn't work properly here
//                    }
//                    lifecycleScope.launch {
//                        val infoRenderable = ViewRenderable.builder()
//                            .setView(requireContext(), R.layout.ar_place_info)
//                            .build(lifecycle)
//                        infoRenderable.whenComplete { t, u ->
//                            tempInfoNode.setRenderable(t)
//                            tempInfoNode.lookAt(sceneView.cameraNode, Direction(0f, 1f, 0f))
//                        }
//                    }
                    placesInRadiusEarthAnchors.add(tempEarthAnchor)
//                    placesInRadiusInfoNodes.add(tempInfoNode)
                    placesInRadiusNodeMap[place] = tempEarthNode
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

    private fun jsonToAr(json: String) {
        if (arRoute == null) {
            try {
                arRoute = Json.decodeFromString<ArRoute>(json)
            } catch (e: Exception) {
                FileLog.e("TAG", "ArData could not be parsed, wrong JSON format - $json")
                Utils.toast("ArData could not be parsed, wrong JSON format")
            }
        }
        FileLog.d("TAG", "Resolved an ArRoute from json: ${arRoute.toString()}")
        arRoute?.let { route ->
            updateState(AppState.RESOLVING)
            anchorNode = ArModelNode().also { anchorNode ->
                anchorNode.position = Position(0f, 0f, 0f)
                anchorNode.parent = sceneView
                anchorNode.resolveCloudAnchor(route.cloudAnchorId) { anchor: Anchor, success: Boolean ->
                    if (success) {
                        updateState(AppState.RESOLVE_SUCCESS)

                        anchorNode.setModel(modelMap[ANCHOR])
                        anchorNode.anchor = anchor
                        anchorNode.isVisible = true
                        cloudAnchorId = anchor.cloudAnchorId
                        route.pointsList.forEach {
                            ArModelNode().also { newNode ->
                                newNode.followHitPosition = false
                                newNode.parent = anchorNode //Set the anchor to the cloudAnchor
                                val pos = anchorNode.localToWorldPosition(it.position.toVector3())
                                newNode.position = Position(pos.x, pos.y, pos.z)
                                newNode.scale = Scale(it.scale, it.scale, it.scale)
                                newNode.rotation = it.rotation
                                newNode.setModel(modelMap[it.modelName])
                                addNode(newNode)
                                placedNew = true
                            }

                        }
                    } else {
                        updateState(AppState.RESOLVE_FAIL)
                    }
                }
            }
        }
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
                            if (IGNORE_GEO_ACC || cameraGeospatialPose.horizontalAccuracy < 5) { //TODO decide threshold for accuracy
                                anchorNode = ArModelNode(PlacementMode.PLANE_HORIZONTAL).also {
                                    it.parent = sceneView
                                    it.position = pNode.position
                                    it.anchor = it.createAnchor()
                                    it.isVisible = false
                                    it.setModel(modelMap[ANCHOR])
                                }
                                startRotation = sceneView.cameraNode.transform.rotation.y
                                calculateLatLongOfPlacementNode(cameraGeospatialPose)
                                updateState(AppState.WAITING_FOR_ANCHOR_CIRCLE)
                            }
                        }
                    }
                }
                AppState.PLACE_OBJECT -> {
                    anchorNode?.let { anchorNode ->
                        ArModelNode(PlacementMode.PLANE_HORIZONTAL).also {

                            val angle = startRotation - sceneView.cameraNode.transform.rotation.y
                            val rotationMatrix = rotation(axis = anchorNode.pose!!.yDirection, angle = angle) //Rotation around the Y-Axis of the anchorPlane

                            it.parent = anchorNode
                            it.followHitPosition = false
                            it.position = anchorNode.worldToLocalPosition(pNode.worldPosition.toVector3()).toFloat3()
                            it.quaternion = rotationMatrix.toQuaternion()
                            it.setModel(modelMap[selectedModel])
                            addNode(it)
                            it.modelScale = Scale(scale, scale, scale)
                        }
                    }
                }
                AppState.PLACE_TARGET -> {
                    anchorNode?.let { anchorNode ->
                        ArModelNode(PlacementMode.PLANE_HORIZONTAL).also {
                            it.rotation = pNode.rotation
                            it.parent = anchorNode
                            it.followHitPosition = false
                            it.position = anchorNode.worldToLocalPosition(pNode.worldPosition.toVector3()).toFloat3()
                            it.setModel(modelMap[TARGET])
                            it.modelScale = Scale(scale, scale, scale)
                            updateState(AppState.TARGET_PLACED)
                            addNode(it)
                        }
                    }
                }
                else -> FileLog.e(TAG, "Invalid state when trying to place object")
            }
        } ?: FileLog.e(TAG, "No placement node available, but onPlace pressed")
    }

    private fun onHost() {
        updateState(AppState.HOSTING)
        anchorNode?.let { anchorNode ->
            anchorNode.hostCloudAnchor(365) { anchor: Anchor, success: Boolean -> //TODO onHostedAnchorAvailable checks for success as well, seems unnecessary
                cloudAnchor(anchor)
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

    }

    private fun onConfirm() {
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
                findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToCreateFragment())
            }
            MainViewModel.NavState.MAPS_TO_EDIT -> {
                viewModel.currentPlace?.let {
                    it.lat = geoLat
                    it.lng = geoLng
                    it.heading = geoHdg
                    it.alt = geoAlt
                    findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToCreateFragment())
                } ?: FileLog.e(TAG, "Navstate is edit, but currentplace is null in ARFragment ")
            }
            else -> FileLog.e(TAG, "Wrong Navstate onClick Confirm is ${viewModel.navState}")
        }
    }

    private fun onResolve() {
        if (navOnly) {
            if (isTracking) {
                cloudAnchor?.detach()
                jsonToAr(viewModel.arDataString)
                binding.arExtendedFab.isEnabled = false
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(10000L)
                    if (appState == AppState.RESOLVING) {
                        cloudAnchor?.detach()
                        withContext(Dispatchers.Main) {
                            updateState(AppState.RESOLVE_FAIL)
                        }
                    }
                }
            } else {
                updateState(AppState.RESOLVE_BUT_NOT_READY)
            }
        } else if (isSearchingMode) {
            if (placesInRadiusNodeMap.isEmpty()) {
                Utils.toast("No Places available around!")
                FileLog.w("O_O", "Trying to resolve inSearchingMode, but no places around.")
            } else {
                var shortestDistance = Float.MAX_VALUE
                var closestNode: Pair<Place, ArNode>
                placesInRadiusNodeMap.keys.forEach { place ->
                    placesInRadiusNodeMap[place]?.let {
                        if (distanceOfCameraToNode(it) < MIN_RESOLVE_DISTANCE) {
                            val node = it
                            FileLog.d("O_O", "Trying to resolve Place: ${place.name} ...")
                            node.resolveCloudAnchor(place.id, onResolveCompleted)
                            //TODO store all resolving anchors to detach onStop
                        }

                    }
                }
                placesInRadiusNodeMap.values.forEach {
                    val distance = distanceOfCameraToNode(it)
                    if (distance < shortestDistance) {
                        shortestDistance = distance
                    }
                }
            }
            //TODO resolve closest or lookingAt
        } else {
            throw IllegalStateException("Button Resolve should only be visible in navOnly mode")
        }
    }

    val onResolveCompleted = { anchor: Anchor, success: Boolean ->
        FileLog.d("O_O", "Resolved an anchor with success: $success")
    }

    private fun distanceOfCameraToNode(node: ArNode): Float {
        val vectorUp = node.pose!!.yDirection

        val cameraTransform = sceneView.cameraNode.transform
        val cameraPos = cameraTransform.position
        val cameraOnPlane = cameraPos.minus(vectorUp.times((cameraPos.minus(node.position)).times(vectorUp)))

        return (node.position.minus(cameraOnPlane)).toVector3().length()
    }

    private fun calculateLatLongOfPlacementNode(cameraGeospatialPose: GeospatialPose) {
        placementNode?.let { node ->
            node.pose?.let { pose ->
                val vectorUp = node.pose!!.yDirection

                val cameraTransform = sceneView.cameraNode.transform
                val cameraPos = cameraTransform.position
                val cameraOnPlane = cameraPos.minus(vectorUp.times((cameraPos.minus(node.position)).times(vectorUp)))
                val distanceOfCameraToGround = (cameraPos.minus(cameraOnPlane)).toVector3().length()

                //Angle between forward and the placementNode should always be ZERO

                val bearingToHit = cameraGeospatialPose.heading
                val distanceToHit = (node.position.minus(cameraOnPlane)).toVector3().length()

                val distanceToHitInKm = distanceToHit / 1000

                val latLng = GeoUtils.getPointByDistanceAndBearing(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude, bearingToHit, distanceToHitInKm.toDouble())
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
                    FileLog.d(TAG, "PlacementNode location calculated: \n$it")
                }
            }
        }
    }

    private fun calculateLatLongOfHitTest(hitResult: HitResult, cameraGeospatialPose: GeospatialPose) {

        val hitNormal = hitResult.hitPose.yDirection //normal vector of the plane going up: n

        val cameraTransform = sceneView.cameraNode.transform
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

        val latLng = GeoUtils.getPointByDistanceAndBearing(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude, bearingToHit, distanceToHitInKm.toDouble())

        // Predicted location not needed, besides debug
//        val predictionAnchor = earth!!.createAnchor(latLng.latitude, latLng.longitude, cameraGeospatialPose.altitude - distanceOfCameraToGround, 0f, 0f, 0f, 1f)
//        earthNode = ArNode(predictionAnchor).also { node ->
//            node.parent = sceneView
//            node.setModel(modelMap[ANCHOR_PREVIEW])
//        }

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
            if (pointList.size == 0) {
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
        //TODO for debug at the moment
        binding.arButtonClear.setOnLongClickListener {
            sceneView.arCameraStream.isDepthOcclusionEnabled = !sceneView.arCameraStream.isDepthOcclusionEnabled
            true
        }
        binding.arModelSizeToggle.addOnButtonCheckedListener { group, checkedId, isChecked ->
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
//        modelMap[AXIS] = GLBLoader.loadModel(requireContext(), lifecycle, "models/axis.glb")
//        modelMap[ANCHOR_SEARCH_ARROW] = GLBLoader.loadModel(requireContext(), lifecycle, "models/small_preview_arrow_blue.glb")

        modelMap[ARROW_FORWARD] = ModelRenderable.builder()
            .setSource(context, parse("models/arrow_fw.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ARROW_LEFT] = ModelRenderable.builder()
            .setSource(context, parse("models/arrow_lf.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ARROW_RIGHT] = ModelRenderable.builder()
            .setSource(context, parse("models/arrow_rd.glb"))
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
        modelMap[AXIS] = ModelRenderable.builder()
            .setSource(context, parse("models/axis.glb"))
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
            it.detachAnchor()
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
        placementNode?.isVisible = false
        anchorCircle.destroy()
        anchorCircle = AnchorHostingPoint(requireContext(), Filament.engine, sceneView.scene)
        anchorCircle.enabled = true
        binding.arFabLayout.visibility = View.GONE
        binding.arModelSizeToggle.visibility = View.INVISIBLE
        binding.arButtonUndo.visibility = View.GONE
        binding.arButtonClear.visibility = View.GONE
        updateState(AppState.PLACE_ANCHOR)
    }

    private fun updateState(state: AppState) {
        appState = state
        binding.arInfoText.text = when (appState) {
            AppState.STARTING_AR -> {
                "AR starting up and trying to gather environment information \n +" +
                        "Please move your phone around while looking at your anchor area"

            }
            AppState.PLACE_ANCHOR -> {
                updateExtendedFab("PLACE")
                binding.arButtonClear.visibility = View.GONE
                binding.arButtonUndo.visibility = View.GONE
                binding.arModelSizeToggle.visibility = View.GONE
                "Place the anchor at the desired location \n \n" +
                        "Make sure the VPS accuracy is good before placing!"
            }
            AppState.WAITING_FOR_ANCHOR_CIRCLE -> {
                binding.arExtendedFab.isEnabled = false
                //TODO potentially change text of button to host anchor
                "Please walk around the circle and scan every side"
            }
            AppState.HOSTING -> {
                "Anchor placed! \n \n" +
                        "Trying to host as cloud anchor... \n" +
                        "Please wait"
            }
            AppState.HOST_SUCCESS -> {
                binding.arButtonUndo.visibility = View.VISIBLE
                binding.arButtonClear.visibility = View.VISIBLE
                "Successfully hosted as cloud anchor \n \n" +
                        "Is the Anchor is perfectly flat on the surface? \n" +
                        "\t No -> Press clear and replace it \n" +
                        "\t Yes -> Select the next arrow model"
            }
            AppState.HOST_FAIL -> {
                "Hosting the cloud anchor failed \n \n" +
                        "Please move your phone around the area more \n" +
                        "then try placing again"
            }
            AppState.PLACE_OBJECT -> {
                updateExtendedFab("PLACE")
                placementNode?.setModel(modelMap[selectedModel])
                placementNode?.modelScale = Scale(scale, scale, scale)
                placementNode?.isVisible = true
                binding.arExtendedFab.isEnabled = true
                binding.arModelSizeToggle.visibility = View.VISIBLE
                "Place the arrow at the desired location \n \n" +
                        "Make sure the last placed object is still in the field of view"
            }
            AppState.SELECT_OBJECT -> {
                "Select the arrow type to place next, by clicking on the Button in the bottom-right"
            }
            AppState.PLACE_TARGET -> {
                updateExtendedFab("PLACE")
                binding.arExtendedFab.isEnabled = true
                placementNode?.setModel(modelMap[selectedModel])
                placementNode?.modelScale = Scale(scale, scale, scale)
                placementNode?.isVisible = true
                binding.arModelSizeToggle.visibility = View.VISIBLE
                "Place the destination marker at the desired location \n \n" +
                        "This will be the last marker to place"
            }
            AppState.TARGET_PLACED -> {
                binding.arModelSizeToggle.visibility = View.GONE
                placementNode?.isVisible = false
                updateExtendedFab("CONFIRM")
                "You have successfully created a full route\n \n" +
                        "Press confirm if everything is ready"
            }
            AppState.RESOLVE_ABLE -> {
                updateExtendedFab("RESOLVE")
                binding.arButtonClear.visibility = View.GONE
                binding.arButtonUndo.visibility = View.GONE
                binding.arModelSizeToggle.visibility = View.GONE
                "An ArRoute is available to be resolved \n \n" +
                        "Walk around the anticipated anchor location first \n" +
                        " and move the phone around as much as possible \n" +
                        "Press resolve when ready"
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
                        "Follow the arrows on your screen to the destination point"
            }
            AppState.RESOLVE_FAIL -> {
                binding.arExtendedFab.isEnabled = true
                "Resolving of cloud anchor failed \n \n" +
                        "Move your phone around the anchor area and then try to resolve again"
            }
            AppState.SEARCHING -> {
                updateExtendedFab("RESOLVE")
                binding.arButtonClear.visibility = View.GONE
                binding.arButtonUndo.visibility = View.GONE
                binding.arModelSizeToggle.visibility = View.GONE
                "Searching Mode \n \n  Anchors of routes are shown, click resolve when you found one"
            }
        }
    }

    private fun updateExtendedFab(type: String) {
        when (type) {
            "PLACE" -> {
                binding.arExtendedFab.text = "PLACE"
                binding.arExtendedFab.icon = requireActivity().getDrawable(R.drawable.ic_place_item_24)
            }
            "CONFIRM" -> {
                binding.arExtendedFab.text = "CONFIRM"
                binding.arExtendedFab.icon = requireActivity().getDrawable(R.drawable.ic_baseline_cloud_upload_24)
            }
            "RESOLVE" -> {
                binding.arExtendedFab.text = "RESOLVE"
                binding.arExtendedFab.icon = requireActivity().getDrawable(R.drawable.ic_baseline_cloud_download_24)
            }
        }
    }

    private fun print() {
        FileLog.d("_PRINT_", "________________________")
        FileLog.d("_PRINT_", "DEBUG print of all nodes")
        FileLog.d("_PRINT_", "AnchorNode = $anchorNode.")
        FileLog.d("_PRINT_", "Position = ${anchorNode?.position}")
        FileLog.d("_PRINT_", "WorldPos = ${anchorNode?.worldPosition}")
        FileLog.d("_PRINT_", "Rotation = ${anchorNode?.rotation}")
        FileLog.d("_PRINT_", "WorldRotation = ${anchorNode?.worldRotation}")
        FileLog.d("_PRINT_", "PlacementNode = $placementNode.")
        FileLog.d("_PRINT_", "Position = ${placementNode?.position}")
        FileLog.d("_PRINT_", "WorldPos = ${placementNode?.worldPosition}")
        FileLog.d("_PRINT_", "Rotation = ${placementNode?.rotation}")
        FileLog.d("_PRINT_", "WorldRotation = ${placementNode?.worldRotation}")

        var i = 1
        for (n in nodeList) {
            FileLog.d("_PRINT_", "____ New Node #$i")
            FileLog.d("_PRINT_", "Node = $n")
            FileLog.d("_PRINT_", "Position = ${n.position}")
            FileLog.d("_PRINT_", "WorldPos = ${n.worldPosition}")
            FileLog.d("_PRINT_", "Rotation = ${n.rotation}")
            FileLog.d("_PRINT_", "WorldRotation = ${n.worldRotation}")
            FileLog.d("_PRINT_", "AnchorPosition= ${n.anchor?.pose?.position}")
            i++
        }
        FileLog.d("_PRINT_", "________________________")
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
        FileLog.d("O_O", "onStop")
        cloudAnchor?.detach()
        sceneView.onStop(this)
        super.onStop()
    }

    override fun onStart() {
        sceneView.onStart(this)
        super.onStart()
    }

    override fun onDestroy() {
        FileLog.d("O_O", "onDestroy")
        sceneView.onDestroy(this)
        _binding = null
        super.onDestroy()
    }
}
