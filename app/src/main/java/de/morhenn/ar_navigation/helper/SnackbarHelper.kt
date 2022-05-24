package de.morhenn.ar_navigation.helper

import android.R
import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.TextView
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar

/*
* Copyright 2017 Google Inc. All Rights Reserved.
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
/**
 * Helper to manage the sample snackbar. Hides the Android boilerplate code, and exposes simpler
 * methods.
 */
class SnackbarHelper {
    private var messageSnackbar: Snackbar? = null

    private enum class DismissBehavior {
        HIDE, SHOW, FINISH
    }

    private val maxLines = 2
    var lastMessage = ""
        private set

    /** Shows a snackbar with a given message.  */
    fun showMessage(activity: Activity, message: String) {
        if (message.isNotEmpty() && (messageSnackbar == null || lastMessage != message)) {
            lastMessage = message
            show(activity, message, DismissBehavior.HIDE)
        }
    }

    /** Shows a snackbar with a given message, and a dismiss button.  */
    fun showMessageWithDismiss(activity: Activity, message: String) {
        lastMessage = message
        show(activity, message, DismissBehavior.SHOW)
    }

    /**
     * Shows a snackbar with a given error message. When dismissed, will finish the activity. Useful
     * for notifying errors, where no further interaction with the activity is possible.
     */
    fun showError(activity: Activity, errorMessage: String) {
        show(activity, errorMessage, DismissBehavior.FINISH)
    }

    private fun show(activity: Activity, message: String, dismissBehavior: DismissBehavior) {
        Log.d("O_O", "I showed a snackbar, but where. Message was $message")
        activity.runOnUiThread {
            messageSnackbar = Snackbar.make(
                activity.findViewById(R.id.content),
                message, Snackbar.LENGTH_LONG
            )
            messageSnackbar!!.view.setBackgroundColor(BACKGROUND_COLOR)
            if (dismissBehavior != DismissBehavior.HIDE) {
                messageSnackbar!!.setAction("Dismiss") { v: View? -> messageSnackbar!!.dismiss() }
                if (dismissBehavior == DismissBehavior.FINISH) {
                    messageSnackbar!!.addCallback(
                        object : BaseCallback<Snackbar?>() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                super.onDismissed(transientBottomBar, event)
                                activity.finish()
                            }
                        })
                }
            }
            (messageSnackbar!!
                .view
                .findViewById<View>(com.google.android.material.R.id.snackbar_text) as TextView).maxLines = maxLines
            messageSnackbar!!.show()
        }
    }

    /**
     * Hides the currently showing snackbar, if there is one. Safe to call from any thread. Safe to
     * call even if snackbar is not shown.
     */
    fun hide(activity: Activity) {
        if (!isShowing) {
            return
        }
        lastMessage = ""
        val messageSnackbarToHide = messageSnackbar
        messageSnackbar = null
        activity.runOnUiThread { messageSnackbarToHide!!.dismiss() }
    }

    val isShowing: Boolean
        get() = messageSnackbar != null

    companion object {
        private const val BACKGROUND_COLOR = -0x40cdcdce
    }
}