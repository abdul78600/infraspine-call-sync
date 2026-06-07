package com.infraspine.callsync.ui.common

/**
 * Wraps a value that should be consumed exactly once (e.g. a one-shot snackbar message
 * surfaced via LiveData). Prevents the same message from re-appearing on configuration
 * changes when LiveData replays its last value to new observers.
 */
open class Event<out T>(private val content: T) {

    var hasBeenHandled = false
        private set

    fun getContentIfNotHandled(): T? =
        if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }

    fun peekContent(): T = content
}
