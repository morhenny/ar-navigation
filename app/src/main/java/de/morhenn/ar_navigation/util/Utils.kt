package de.morhenn.ar_navigation.util

import android.content.Context
import android.widget.Toast

object Utils {
    private lateinit var context: Context

    fun init(context: Context) {
        Utils.context = context
    }

    fun toast(message: String, long: Boolean = true, context: Context = Utils.context) {
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        FileLog.w("TOAST", message)
    }
}