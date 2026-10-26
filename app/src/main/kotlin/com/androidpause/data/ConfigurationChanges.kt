package com.androidpause.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes configuration commits with foreground accounting and timer transitions. */
object ConfigurationChanges {
    private val mutex = Mutex()
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Runs under [mutex] before each write: flush usage under the old configuration and drop cached state. */
    var beforeChange: (suspend () -> Unit)? = null

    /** One queue for service transitions, teardown accounting and configuration writes. Not reentrant. */
    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> mutate(block: suspend () -> T): T =
        serialized {
            try {
                beforeChange?.invoke()
                block()
            } finally {
                // A failed hook or write must also resume the canceled timers.
                events.tryEmit(Unit)
            }
        }
}
