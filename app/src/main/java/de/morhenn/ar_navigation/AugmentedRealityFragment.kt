package de.morhenn.ar_navigation

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.ar.core.*
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.exceptions.FatalException
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import de.morhenn.ar_navigation.AugmentedRealityFragment.ModelName.*
import de.morhenn.ar_navigation.databinding.FragmentAugmentedRealityBinding
import de.morhenn.ar_navigation.helper.CloudAnchorManager
import de.morhenn.ar_navigation.model.ArPoint
import de.morhenn.ar_navigation.model.ArRoute
import de.morhenn.ar_navigation.util.FileLog
import de.morhenn.ar_navigation.util.GeoUtils
import de.morhenn.ar_navigation.util.Utils
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.arcore.ArSession
import io.github.sceneview.ar.arcore.isTracking
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.arcore.yDirection
import io.github.sceneview.ar.node.ArNode
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.math.*
import io.github.sceneview.model.await
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
        private const val RENDER_DISTANCE = 100f //default is 30
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
    }

    enum class AppState {
        STARTING_AR, //"Searching surfaces"
        PLACE_ANCHOR,
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
    }

    enum class ModelName {
        ARROW_FORWARD,
        ARROW_LEFT,
        ARROW_RIGHT,
        CUBE,
        ANCHOR,
        ANCHOR_PREVIEW,
        TARGET,
        AXIS,
    }

    private var _binding: FragmentAugmentedRealityBinding? = null
    private val binding get() = _binding!!

    private lateinit var sceneView: ArSceneView
    private var anchorNode: ArNode? = null
    private var nodeList: MutableList<ArNode> = ArrayList()
    private var pointList: MutableList<ArPoint> = ArrayList()
    private val adapter = MyListAdapter(pointList)

    private var modelMap: EnumMap<ModelName, Renderable> = EnumMap(ModelName::class.java)
    private var cloudAnchor: Anchor? = null
    private var cloudAnchorId: String? = ""
    private var arRoute: ArRoute? = null
    private var navOnly = false //true = create/edit a route    false = navigate a route
    private var startRotation = 0f
    private var scale = 1.5f
    private var isTracking = false
    private var placedNew = false

    var geoLat = 0.0
    var geoLng = 0.0
    var geoAlt = 0.0
    var geoHdg = 0.0

    private var earthAnchorPlaced = false
    private var earthNode: ArNode? = null

    private var appState: AppState = AppState.STARTING_AR
    private var selectedModel: ModelName = ANCHOR

    private val cloudAnchorManager = CloudAnchorManager()

    private val viewModel: MainViewModel by navGraphViewModels(R.id.nav_graph_xml)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAugmentedRealityBinding.inflate(inflater, container, false)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            FileLog.d("O_O", "Is on back happening? with state: ${viewModel.navState}")
            when (viewModel.navState) {
                MainViewModel.NavState.NONE -> throw IllegalStateException("this navState should never be possible in ArFragment")
                MainViewModel.NavState.CREATE_TO_AR_TO_TRY -> findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToCreateFragment())
                MainViewModel.NavState.MAPS_TO_EDIT -> findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToCreateFragment())
                MainViewModel.NavState.MAPS_TO_AR_NEW -> findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToMapsFragment())
                MainViewModel.NavState.MAPS_TO_AR_NAV -> findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToMapsFragment())
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launchWhenCreated {
            loadModels()
        }
        sceneView.camera.farClipPlane = RENDER_DISTANCE
        //sceneView.arCameraStream.isDepthOcclusionEnabled = true //this needs to be called after placing is complete

        sceneView = binding.sceneView
        sceneView.configureSession { _: ArSession, config: Config ->
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL //horizontal for now, potentially try out vertical for target later
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.geospatialMode = Config.GeospatialMode.ENABLED
        }
        sceneView.planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_TOP_MOST
        //sceneView.planeRenderer.isShadowReceiver = false

        sceneView.onArSessionCreated = {
            FileLog.w("O_O", "Session is created: $it")
        }
        sceneView.onArSessionFailed = {
            FileLog.w("O_O", "Session failed with exception: $it")

        }

        sceneView.lifecycle.addObserver(onArFrame = { arFrame ->
            cloudAnchorManager.onUpdate()
            arFrame.updatedPlanes.forEach { plane ->
                val normalVector = plane.centerPose.yDirection //normal vector of the plane going up
                //Calculate distance between planes and resolved objects
                if (placedNew) {
                    placedNew = false
                    FileLog.d("O_O", "____")
                    nodeList.forEach {
                        //normalVector = it.worldToLocalPosition(normalVector.toVector3()).toFloat3()
                        val centerPos = it.worldToLocalPosition(plane.centerPose.position.toVector3()).toFloat3()
                        val projectedNode = it.position.minus(normalVector.times((it.position.minus(centerPos)).times(normalVector)))
                        val distance = projectedNode.minus(it.position).toVector3().length()
                        FileLog.d("O_O", "Distance to plane for this node: $distance")
                    }
                }
            }
            if (navOnly && !isTracking && arFrame.isTrackingPlane) {
                isTracking = true
            }
            val earth = sceneView.arSession?.earth ?: return@addObserver
            if (earth.trackingState == TrackingState.TRACKING) {
                earthIsTrackingLoop(earth)
            }
        })

        initOnTouch()
        initUI()

        val arData = viewModel.arDataString
        if (arData.isNotBlank()) {
            sceneView.instructions.enabled = false
            binding.arButtonConfirm.visibility = View.GONE
            binding.arButtonClear.visibility = View.GONE
            binding.arButtonUndo.visibility = View.GONE
            navOnly = true
            updateState(AppState.RESOLVE_ABLE)
        } else {
            binding.arButtonResolve.visibility = View.GONE
            binding.arButtonDone.visibility = View.GONE
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
        binding.viewAccVerticalRaw.text = String.format("%.2fm", cameraGeospatialPose.verticalAccuracy)
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

        if (!earthAnchorPlaced) {
            viewModel.currentPlace?.let {
                if (cameraGeospatialPose.horizontalAccuracy < 2) {
                    val earthAnchor = earth.createAnchor(it.lat, it.lng, it.alt ?: cameraGeospatialPose.altitude - 1, 0f, 0f, 0f, 1f)
                    earthAnchorPlaced = true
                    earthNode = ArNode(earthAnchor)
                    earthNode?.let { earthNode ->
                        earthNode.parent = sceneView
                        earthNode.setModel(modelMap[ANCHOR_PREVIEW])
                    }
                }
                //FileLog.d("VPS", "Earth Node was placed at ${earthAnchor.pose.position}")
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
        arRoute?.let {
            updateState(AppState.RESOLVING)
            if (it.cloudAnchorId.isNotBlank()) {
                sceneView.arSession?.let { session ->
                    cloudAnchorManager.clearListeners()
                    cloudAnchorManager.resolveCloudAnchor(session, it.cloudAnchorId, object : CloudAnchorManager.CloudAnchorListener {
                        override fun onCloudTaskComplete(anchor: Anchor) {
                            onResolvedAnchorAvailable(anchor)
                        }
                    })
                }
            }
        }
    }

    private fun cloudAnchor(newAnchor: Anchor?) {
        cloudAnchor?.detach()
        cloudAnchor = newAnchor
    }

    private fun initOnTouch() {
        sceneView.onTouchAr = { hitResult, motionEvent ->
            if (appState == AppState.HOST_FAIL) {
                appState = AppState.PLACE_ANCHOR
            }
            if (!navOnly) {
                if (hitResult.isTracking) {
                    val node = ArNode()
                    when (appState) {
                        AppState.PLACE_ANCHOR -> {
                            if (modelMap[ANCHOR] == null) {
                                Utils.toast("Error loading the model, please try again")
                            } else {
                                try {
                                    sceneView.arSession?.earth?.let { earth ->
                                        if (earth.trackingState == TrackingState.TRACKING) {
                                            val cameraGeospatialPose = earth.cameraGeospatialPose
                                            if (IGNORE_GEO_ACC || (cameraGeospatialPose.horizontalAccuracy < H_ACC_1 && cameraGeospatialPose.horizontalAccuracy < V_ACC_1 && cameraGeospatialPose.headingAccuracy < HEAD_ACC_1)) {
                                                // Calculation of the LAT/LONG/HEADING of the hit-test location to place a geospatial anchor
                                                val hitPlane = hitResult.trackable
                                                if (hitPlane is Plane) {

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

                                                    val latLng = GeoUtils.getPointByDistanceAndBearing(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude, bearingToHit, distanceToHitInKm.toDouble())

                                                    val predictionAnchor = earth.createAnchor(latLng.latitude, latLng.longitude, cameraGeospatialPose.altitude - distanceOfCameraToGround, 0f, 0f, 0f, 1f)
                                                    earthNode = ArNode(predictionAnchor)
                                                    earthNode?.let {
                                                        it.parent = sceneView
                                                        it.setModel(modelMap[ANCHOR_PREVIEW])
                                                    }

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
                                                        FileLog.d("O_O", it)
                                                    }
                                                } else {
                                                    Utils.toast("Plane not detected and the trackable is $hitPlane")
                                                }

                                                val anchor = hitResult.createAnchor()
                                                sceneView.arSession?.let {
                                                    it.hostCloudAnchorWithTtl(anchor, 365)
                                                    cloudAnchorManager.hostCloudAnchor(it, anchor, /* ttl= */ 365, object : CloudAnchorManager.CloudAnchorListener {
                                                        override fun onCloudTaskComplete(anchor: Anchor) {
                                                            onHostedAnchorAvailable(anchor)
                                                        }
                                                    })
                                                }
                                                cloudAnchor(anchor)
                                                updateState(AppState.HOSTING)
                                                node.position = Position(0f, 0f, 0f)
                                                node.anchor = anchor
                                                node.parent = sceneView
                                                node.setModel(modelMap[ANCHOR])
                                                sceneView.addChild(node)
                                                anchorNode = node
                                                startRotation = sceneView.camera.transform.rotation.y
                                                binding.arButtonUndo.isEnabled = true
                                            } else {
                                                //TODO update status text
                                                Utils.toast("You need to move your phone around in an area with streetview first, to place the anchor")
                                            }
                                        }
                                    }
                                } catch (e: FatalException) { //Sometimes creating anchor might crash
                                    FileLog.e(TAG, e)
                                }
                            }
                        }
                        AppState.PLACE_OBJECT -> {
                            if (selectedModel == ARROW_FORWARD || selectedModel == ARROW_LEFT || selectedModel == ARROW_RIGHT || selectedModel == TARGET) {
                                anchorNode?.let {
                                    node.parent = it //parent of each object will be the cloudAnchor
                                    val pos = it.worldToLocalPosition(hitResult.hitPose.position.toVector3()).apply { y = 0f }
                                    val angle = startRotation - sceneView.camera.transform.rotation.y
                                    val rotationMatrix = rotation(axis = it.pose!!.yDirection, angle = angle) //Rotation around the Y-Axis of the anchorPlane

                                    node.position = pos.toFloat3()
                                    node.quaternion = rotationMatrix.toQuaternion()
                                    node.setModel(modelMap[selectedModel])
                                    addNode(node)
                                    node.modelScale = Scale(scale, scale, scale)
                                } ?: throw IllegalStateException("Error onTouchAr: trying to place object, but anchor is null")
                            } else {
                                Utils.toast("Please select the type of object to place!")
                            }
                        }
                        AppState.PLACE_TARGET -> {
                            if (selectedModel == TARGET) {
                                anchorNode?.let {
                                    //Rotate the Object around the Y-Axis to match the cameras rotation
                                    node.rotation = Rotation(Quaternion(Vector3(0f, 1f, 0f), sceneView.camera.rotation.y / 2).eulerAngles.toFloat3())
                                    node.parent = it
                                    val pos = it.worldToLocalPosition(hitResult.hitPose.position.toVector3()).apply { y = 0f }
                                    node.position = Position(pos.x, pos.y, pos.z)
                                    node.setModel(modelMap[TARGET])
                                    addNode(node)
                                    node.modelScale = Scale(scale, scale, scale)
                                    updateState(AppState.TARGET_PLACED)
                                } ?: throw IllegalStateException("Error onTouchAr: trying to place object, but anchor is null")
                            } else {
                                throw IllegalStateException("Error onTouchAr")
                            }
                        }
                        else -> FileLog.w(TAG, "Invalid state when trying to place object")
                    }
                } else {
                    Utils.toast("HITRESULT NOT TRACKING ontap!!")
                    FileLog.d(TAG, "AR was pressed, but is not tracking yet")
                }
            } else {
                Utils.toast("This is Nav only mode, no tapping allowed :)")
            }
        }
    }

    @Synchronized
    fun onHostedAnchorAvailable(anchor: Anchor) {
        when (anchor.cloudAnchorState) {
            CloudAnchorState.SUCCESS -> {
                cloudAnchorId = anchor.cloudAnchorId
                updateState(AppState.HOST_SUCCESS)
                binding.arFabLayout.visibility = View.VISIBLE
            }
            CloudAnchorState.ERROR_HOSTING_DATASET_PROCESSING_FAILED -> {
                updateState(AppState.HOST_FAIL)
                clear()
            }
            else -> {
                updateState(AppState.HOST_FAIL)
                clear()
            }
        }
    }

    @Synchronized
    fun onResolvedAnchorAvailable(anchor: Anchor) {
        val cloudState = anchor.cloudAnchorState
        if (cloudState == CloudAnchorState.SUCCESS) {
            updateState(AppState.RESOLVE_SUCCESS)
            val node = ArNode()
            node.position = Position(0f, 0f, 0f)
            node.anchor = anchor
            node.parent = sceneView
            node.setModel(modelMap[ANCHOR])
            anchorNode = node
            cloudAnchorId = anchor.cloudAnchorId

            arRoute?.let { route ->
                route.pointsList.forEach {
                    val newNode = ArNode()
                    newNode.parent = anchorNode //Set the anchor to the cloudAnchor
                    val pos = anchorNode!!.localToWorldPosition(it.position.toVector3()).apply { y = 0f }
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
            FileLog.d("TAG", "Error while resolving anchor with id ${anchor.cloudAnchorId}. Error: $cloudState")
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
        binding.arButtonResolve.setOnClickListener {
            if (navOnly) {
                if (isTracking) {
                    cloudAnchorManager.detachAllAnchors()
                    cloudAnchorManager.clearListeners()
                    cloudAnchor = null
                    jsonToAr(viewModel.arDataString)
                    binding.arButtonResolve.isEnabled = false
                    lifecycleScope.launch(Dispatchers.IO) {
                        delay(10000L)
                        if (appState == AppState.RESOLVING) {
                            cloudAnchorManager.detachAllAnchors()
                            cloudAnchorManager.clearListeners()
                            cloudAnchor = null
                            withContext(Dispatchers.Main) {
                                updateState(AppState.RESOLVE_FAIL)
                            }
                        }
                    }
                } else {
                    updateState(AppState.RESOLVE_BUT_NOT_READY)
                }
            } else {
                throw IllegalStateException("Button Resolve should only be visible in navOnly mode")
            }

        }
        binding.arButtonUndo.setOnClickListener {
            if (pointList.size == 0) {
                clear()
            } else if (pointList.size > 0) {
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
        binding.arButtonClear.setOnClickListener {
            clear()
        }
        //TODO for debug at the moment
        binding.arButtonClear.setOnLongClickListener {
            sceneView.arCameraStream.isDepthOcclusionEnabled = !sceneView.arCameraStream.isDepthOcclusionEnabled
            true
        }
        binding.arButtonConfirm.setOnClickListener {
            if (appState == AppState.TARGET_PLACED) {
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
                        } ?: FileLog.e("O_O", "Navstate is edit, but currentplace is null in ARFragment ")
                    }
                    else -> FileLog.e("O_O", "Wrong Navstate onClick Confirm is ${viewModel.navState}")
                }
            } else {
                Utils.toast("You need to place your destination target first.")
            }
        }
        binding.arButtonDone.setOnClickListener {
            when (viewModel.navState) {
                MainViewModel.NavState.CREATE_TO_AR_TO_TRY -> findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToCreateFragment())
                MainViewModel.NavState.MAPS_TO_AR_NAV -> findNavController().navigate(AugmentedRealityFragmentDirections.actionArFragmentToMapsFragment())
                else -> throw IllegalStateException("this navState should never be possible in ArFragment")
            }
        }
        binding.arButtonDone.setOnLongClickListener {
            placedNew = true
            true
        }
        binding.arModelFrameS.setOnClickListener {
            scale = 1f
            binding.arModelFrameS.setBackgroundResource(R.drawable.rounded_corners_highlighted)
            binding.arModelFrameM.setBackgroundResource(R.drawable.rounded_corners)
            binding.arModelFrameL.setBackgroundResource(R.drawable.rounded_corners)
        }
        binding.arModelFrameM.setOnClickListener {
            scale = 1.5f
            binding.arModelFrameS.setBackgroundResource(R.drawable.rounded_corners)
            binding.arModelFrameM.setBackgroundResource(R.drawable.rounded_corners_highlighted)
            binding.arModelFrameL.setBackgroundResource(R.drawable.rounded_corners)
        }
        binding.arModelFrameL.setOnClickListener {
            scale = 2f
            binding.arModelFrameS.setBackgroundResource(R.drawable.rounded_corners)
            binding.arModelFrameM.setBackgroundResource(R.drawable.rounded_corners)
            binding.arModelFrameL.setBackgroundResource(R.drawable.rounded_corners_highlighted)
        }
    }

    private fun setModelIcons(iconId: Int) {
        binding.arModelIconS.setImageResource(iconId)
        binding.arModelIconM.setImageResource(iconId)
        binding.arModelIconL.setImageResource(iconId)
    }

    private suspend fun loadModels() {
        modelMap[ARROW_FORWARD] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/arrow_fw.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ARROW_LEFT] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/arrow_lf.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ARROW_RIGHT] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/arrow_rd.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[CUBE] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/cube.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ANCHOR] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/anchor.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[ANCHOR_PREVIEW] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/anchor_preview.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[TARGET] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/target.glb"))
            .setIsFilamentGltf(true)
            .await(lifecycle)
        modelMap[AXIS] = ModelRenderable.builder()
            .setSource(context, Uri.parse("models/axis.glb"))
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

    private fun clear() {
        nodeList.forEach {
            it.parent = null
            it.detachAnchor()
        }
        val lastIndex = pointList.lastIndex
        nodeList.clear()
        pointList.clear()
        adapter.notifyItemRangeRemoved(0, lastIndex)
        setModelIcons(R.drawable.ic_baseline_photo_size_select_large_24)
        anchorNode?.parent = null
        anchorNode = null
        cloudAnchor = null
        earthNode?.parent = null
        earthNode = null
        binding.arFabLayout.visibility = View.GONE
        binding.arModelFrameS.visibility = View.GONE
        binding.arModelFrameM.visibility = View.GONE
        binding.arModelFrameL.visibility = View.GONE
        binding.arButtonUndo.isEnabled = false
        updateState(AppState.PLACE_ANCHOR)
    }

    private fun addNode(node: ArNode) {
        nodeList.add(node)
        pointList.add(ArPoint(node.position, node.rotation, findModelName(node.model), scale))
        adapter.notifyItemInserted(adapter.itemCount)

    }

    private fun updateState(state: AppState) {
        appState = state
        binding.arInfoText.text = when (appState) {
            AppState.STARTING_AR -> {
                "AR starting up and trying to gather environment information \n +" +
                        "Please move your phone around while looking at your anchor area"

            }
            AppState.PLACE_ANCHOR -> {
                "Place the anchor, by tapping on a surface \n \n" +
                        "Make sure this anchor is where it's placed on the map"
            }
            AppState.HOSTING -> {
                "Anchor placed! \n \n" +
                        "Trying to host as cloud anchor... \n" +
                        "Please wait"
            }
            AppState.HOST_SUCCESS -> {
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
                binding.arModelFrameS.visibility = View.VISIBLE
                binding.arModelFrameM.visibility = View.VISIBLE
                binding.arModelFrameL.visibility = View.VISIBLE
                "Place the arrow, by tapping on a surface \n \n" +
                        "Make sure the last placed object is still in the field of view"
            }
            AppState.SELECT_OBJECT -> {
                "Select the arrow type to place next, by clicking on the Button in the bottom-right"
            }
            AppState.PLACE_TARGET -> {
                binding.arModelFrameS.visibility = View.VISIBLE
                binding.arModelFrameM.visibility = View.VISIBLE
                binding.arModelFrameL.visibility = View.VISIBLE
                "Place the destination marker by tapping on the surface \n \n" +
                        "This will be the last marker to place"
            }
            AppState.TARGET_PLACED -> {
                binding.arModelFrameS.visibility = View.GONE
                binding.arModelFrameM.visibility = View.GONE
                binding.arModelFrameL.visibility = View.GONE
                "You have successfully created a full route\n \n" +
                        "Press confirm if everything is ready"
            }
            AppState.RESOLVE_ABLE -> {
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
                binding.arButtonResolve.isEnabled = false
                "Trying to resolve the cloud anchor... \n \n" +
                        "Please wait a couple seconds and potentially try again if it doesn't load"
            }
            AppState.RESOLVE_SUCCESS -> {
                binding.arButtonResolve.isEnabled = true
                "Successfully resolved the cloud anchor and the attached route \n \n" +
                        "Follow the arrows on your screen to the destination point"
            }
            AppState.RESOLVE_FAIL -> {
                binding.arButtonResolve.isEnabled = true
                "Resolving of cloud anchor failed \n \n" +
                        "Move your phone around the anchor area and then try to resolve again"
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
        cloudAnchorManager.detachAllAnchors()
        cloudAnchorManager.clearListeners()
        sceneView.onStop(this)
        super.onStop()
    }

    override fun onStart() {
        sceneView.onStart(this)
        super.onStart()
    }

    override fun onDestroy() {
        sceneView.onDestroy(this)
        _binding = null
        super.onDestroy()
    }
}