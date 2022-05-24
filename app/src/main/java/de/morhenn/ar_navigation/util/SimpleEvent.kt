package de.morhenn.ar_navigation.util

/**
 * Used as LiveData that represents an event. In contrast to a normal Event the SimpleEvent does not contain any data.
 * Usually used as a Click-Event or to open dialogs
 */
open class SimpleEvent() {

    var hasBeenHandled = false
        private set // Allow external read but not write
        get() { //only return false once
            return if (!field) {
                field = true
                false
            } else {
                field
            }
        }
}