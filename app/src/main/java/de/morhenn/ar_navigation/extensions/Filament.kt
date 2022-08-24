package de.morhenn.ar_navigation.extensions

import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.Material
import java.nio.ByteBuffer
import java.nio.channels.Channels

fun loadMaterial(context: Context, engine: Engine, name: String): Material {
    val dst: ByteBuffer

    context.assets.openFd("materials/$name").use { fd ->
        val input = fd.createInputStream()
        dst = ByteBuffer.allocate(fd.length.toInt())

        val src = Channels.newChannel(input)
        src.read(dst)
        src.close()

        dst.rewind()
    }

    return Material.Builder().payload(dst, dst.remaining()).build(engine)
}
