package de.morhenn.ar_navigation.helper

import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Session

/*
* Copyright 2019 Google Inc. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
/**
 * A helper class to handle all the Cloud Anchors logic, and add a callback-like mechanism on top of
 * the existing ARCore API.
 */
class CloudAnchorManager {
    /** Listener for the results of a host or resolve operation.  */
    interface CloudAnchorListener {
        /** This method is invoked when the results of a Cloud Anchor operation are available.  */
        fun onCloudTaskComplete(anchor: Anchor)
    }

    private val pendingAnchors = HashMap<Anchor, CloudAnchorListener>()

    /**
     * This method hosts an anchor. The `listener` will be invoked when the results are
     * available.
     */
    @Synchronized
    fun hostCloudAnchor(
        session: Session, anchor: Anchor?, ttl: Int, listener: CloudAnchorListener
    ) {
        val newAnchor = session.hostCloudAnchorWithTtl(anchor, ttl)
        pendingAnchors[newAnchor] = listener
    }

    /**
     * This method resolves an anchor. The `listener` will be invoked when the results are
     * available.
     */
    @Synchronized
    fun resolveCloudAnchor(
        session: Session, anchorId: String?, listener: CloudAnchorListener
    ) {
        val newAnchor = session.resolveCloudAnchor(anchorId)
        pendingAnchors[newAnchor] = listener
    }

    /** Should be called after a [Session.update] call.  */
    @Synchronized
    fun onUpdate() {
        val iter: MutableIterator<Map.Entry<Anchor, CloudAnchorListener>> = pendingAnchors.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val anchor = entry.key

            if (isReturnableState(anchor.cloudAnchorState)) {
                val listener = entry.value
                listener.onCloudTaskComplete(anchor)
                iter.remove()
            }
        }
    }

    /** Used to clear any currently registered listeners, so they wont be called again.  */
    @Synchronized
    fun clearListeners() {
        pendingAnchors.clear()
    }

    @Synchronized
    fun detachAllAnchors(){
        pendingAnchors.keys.forEach {
            it.detach()
        }
    }

    companion object {
        private fun isReturnableState(cloudState: CloudAnchorState): Boolean {
            return when (cloudState) {
                CloudAnchorState.NONE, CloudAnchorState.TASK_IN_PROGRESS -> false
                else -> true
            }
        }
    }
}