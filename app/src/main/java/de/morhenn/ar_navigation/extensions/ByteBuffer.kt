package de.morhenn.ar_navigation.extensions

import java.nio.ByteBuffer

fun ByteBuffer.putShort(value: Int): ByteBuffer = putShort(value.toShort())

fun ByteBuffer.putVertex(x: Float, y: Float, z: Float): ByteBuffer {
    putFloat(x)
    putFloat(y)
    putFloat(z)
    return this
}

fun ByteBuffer.putTriangle(first: Int, second: Int, third: Int): ByteBuffer {
    putShort(first)
    putShort(second)
    putShort(third)
    return this
}
