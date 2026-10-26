package com.androidpause.enforcement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Arms a single cancellable coroutine timer for a package deadline.
 * Used for the next mid-session interruption and for schedule window boundaries.
 * The callback runs under the owner's [serialized] lock and is skipped if the deadline was
 * cancelled or re-armed while it waited for that lock.
 */
class DeadlineMonitor(
    private val scope: CoroutineScope,
    private val serialized: suspend (suspend () -> Unit) -> Unit,
    private val onDeadlineReached: suspend (pkg: String) -> Unit,
) {
    private var job: Job? = null

    /** Changes on every cancel, so a fired deadline can tell whether it is still current. */
    private var generation = 0L

    fun arm(
        pkg: String,
        delayMillis: Long,
    ) {
        cancel()
        val armedGeneration = generation
        lateinit var armedJob: Job
        armedJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (delayMillis > 0) delay(delayMillis)
                    if (job == armedJob) job = null
                    serialized { if (generation == armedGeneration) onDeadlineReached(pkg) }
                } finally {
                    if (job == armedJob) job = null
                }
            }
        job = armedJob
        armedJob.start()
    }

    fun cancel() {
        job?.cancel()
        job = null
        generation++
    }
}
