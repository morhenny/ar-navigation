package de.morhenn.ar_navigation.model

import de.morhenn.ar_navigation.fragments.AugmentedRealityFragment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import kotlinx.serialization.Serializable

@Serializable
data class ArPoint(var x: Float, var y: Float, var z: Float, var rotX: Float, var rotY: Float, var rotZ: Float, var modelName: AugmentedRealityFragment.ModelName, var scale: Float = 1.5f) {

    val position get() = Position(x, y, z)

    val rotation get() = Rotation(rotX, rotY, rotZ)

    constructor(position: Position, rotation: Rotation, modelName: AugmentedRealityFragment.ModelName, scale: Float = 1.5f) : this(position.x, position.y, position.z, rotation.x, rotation.y, rotation.z, modelName, scale)
}
