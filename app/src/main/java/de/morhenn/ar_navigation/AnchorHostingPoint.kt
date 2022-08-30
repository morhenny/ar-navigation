package de.morhenn.ar_navigation

import android.content.Context
import android.opengl.Matrix
import com.google.android.filament.*
import com.google.ar.core.Camera
import com.google.ar.core.Pose
import de.morhenn.ar_navigation.extensions.loadMaterial
import de.morhenn.ar_navigation.extensions.putShort
import de.morhenn.ar_navigation.extensions.putVertex
import io.github.sceneview.math.Position
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AnchorHostingPoint(
    context: Context,
    private val engine: Engine,
    private val scene: Scene,
) {

    companion object {

        private const val INNER_RADIUS = 0.15f
        private const val OUTER_RADIUS = 0.25f
        private const val CIRCLE_SEGMENTS_COUNT = 40
        private const val COLOR_SEGMENTS_COUNT = 10
        private const val WHITE = 0xffffffff.toInt()
        private const val GREEN = 0xff00ff00.toInt()

    }

    @Entity
    private val renderable: Int
    private val material: Material
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val colorData: ByteBuffer

    private var x = 0f
    private var y = 0f
    private var z = 0f

    private val highlightedSegments = BooleanArray(COLOR_SEGMENTS_COUNT)
    private val matrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val worldPosition = FloatArray(4)
    private val clipSpacePosition = FloatArray(4)

    var enabled: Boolean = false
        set(value) {
            if (value != field) {
                field = value

                if (value) scene.addEntity(renderable)
                else scene.removeEntity(renderable)
            }
        }

    val allSegmentsHighlighted
        get() = highlightedSegments.all { it }

    init {
        material = loadMaterial(context, engine, "anchor_hosting_point_1_20.filamat")

        val n = CIRCLE_SEGMENTS_COUNT

        val intSize = 4
        val floatSize = 4
        val shortSize = 2

        val vertexSize = 3 * floatSize
        val vertexCount = n * 2
        val indexCount = n * 2 * 3
        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())

        for (i in 1..n) {
            val angle = PI.toFloat() * 2 * i / n

            val x = cos(angle)
            val z = -sin(angle)

            vertexData.putVertex(x * OUTER_RADIUS, 0f, z * OUTER_RADIUS)
            vertexData.putVertex(x * INNER_RADIUS, 0f, z * INNER_RADIUS)
        }

        vertexData.flip()
        colorData = ByteBuffer.allocate(vertexCount * intSize).order(ByteOrder.nativeOrder())
        for (i in 1..n) {
            colorData.putInt(WHITE)
            colorData.putInt(WHITE)
        }

        colorData.flip()

        vertexBuffer = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, vertexSize)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, intSize)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)

        vertexBuffer.apply {
            setBufferAt(engine, 0, vertexData)
            setBufferAt(engine, 1, colorData)
        }

        val indexData = ByteBuffer.allocate(indexCount * shortSize).order(ByteOrder.nativeOrder())

        for (i in 0 until n) {
            indexData.putShort(i * 2)
            indexData.putShort((i + 1) % n * 2)
            indexData.putShort(i * 2 + 1)
            indexData.putShort((i + 1) % n * 2)
            indexData.putShort((i + 1) % n * 2 + 1)
            indexData.putShort(i * 2 + 1)
        }

        indexData.flip()

        indexBuffer = IndexBuffer.Builder()
            .indexCount(indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)

        indexBuffer.setBuffer(engine, indexData)

        renderable = EntityManager.get().create()

        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, OUTER_RADIUS, 0.01f, OUTER_RADIUS))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, indexCount)
            .material(0, material.defaultInstance)
            .build(engine, renderable)
    }

    fun setPosition(pose: Pose) {
        x = pose.tx()
        y = pose.ty() + 0.01f
        z = pose.tz()

        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, x, y, z)

        val transformManager = engine.transformManager
        transformManager.setTransform(transformManager.getInstance(renderable), matrix)
    }

    fun position(): Position {
        return Position(x, y, z)
    }

    fun destroy() {
        engine.apply {
            destroyEntity(renderable)
            destroyMaterial(material)
            destroyVertexBuffer(vertexBuffer)
            destroyIndexBuffer(indexBuffer)
        }
    }

    fun highlightSegment(cameraPose: Pose) {
        val dx = cameraPose.tx() - x
        val dz = cameraPose.tz() - z

        var angle = -atan2(dz, dx)

        if (angle < 0) angle += (PI * 2).toFloat()

        val index = floor(angle / (PI * 2) * COLOR_SEGMENTS_COUNT).toInt()

        if (!highlightedSegments[index]) {
            highlightedSegments[index] = true
            updateColorData(index)
        }
    }

    fun highlightAllSegments() {
        val n = CIRCLE_SEGMENTS_COUNT

        for (i in 1..n) {
            colorData.putInt(GREEN)
            colorData.putInt(GREEN)
        }

        colorData.rewind()

        vertexBuffer.setBufferAt(engine, 1, colorData)
    }

    fun isInFrame(camera: Camera): Boolean {
        camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 30f)
        camera.getViewMatrix(viewMatrix, 0)

        Matrix.multiplyMM(matrix, 0, projectionMatrix, 0, viewMatrix, 0)

        worldPosition[0] = x
        worldPosition[1] = y
        worldPosition[2] = z
        worldPosition[3] = 1f

        Matrix.multiplyMV(clipSpacePosition, 0, matrix, 0, worldPosition, 0)

        val clipSpaceX = clipSpacePosition[0] / clipSpacePosition[3]
        val clipSpaceY = clipSpacePosition[1] / clipSpacePosition[3]

        return clipSpaceX in -1f..1f && clipSpaceY in -1f..1f
    }

    private fun updateColorData(index: Int) {
        val n = CIRCLE_SEGMENTS_COUNT
        val ratio = CIRCLE_SEGMENTS_COUNT / COLOR_SEGMENTS_COUNT

        val intSize = 4
        for (i in 1..n) {
            when {
                i >= index * ratio && i <= (index + 1) * ratio + 1
                        || index == COLOR_SEGMENTS_COUNT - 1 && i <= 1 -> {
                    colorData.putInt(GREEN)
                    colorData.putInt(GREEN)
                }
                else -> {
                    colorData.position(colorData.position() + 2 * intSize)
                }
            }
        }

        colorData.rewind()
        vertexBuffer.setBufferAt(engine, 1, colorData)
    }
}
