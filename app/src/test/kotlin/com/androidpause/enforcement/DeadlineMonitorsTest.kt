package com.androidpause.enforcement

import com.androidpause.data.ConfigurationChanges
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeadlineMonitorsTest {
    @Test
    fun `immediate dispatcher can fire and rearm a zero delay deadline`() =
        runTest(UnconfinedTestDispatcher()) {
            val fired = mutableListOf<String>()
            lateinit var monitor: DeadlineMonitor
            monitor =
                DeadlineMonitor(this, { it() }) {
                    fired += it
                    if (it == "first") monitor.arm("second", 100)
                }
            monitor.arm("first", 0)
            assertEquals(listOf("first"), fired)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf("first", "second"), fired)
        }

    @Test
    fun `deadline monitor fires once at threshold`() =
        runTest {
            val fired = mutableListOf<String>()
            val monitor = DeadlineMonitor(this, { it() }) { fired += it }

            monitor.arm("com.example.app", 1_000)
            advanceTimeBy(999)
            runCurrent()
            assertTrue(fired.isEmpty())

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("com.example.app"), fired)
        }

    @Test
    fun `rearming monitor cancels stale package`() =
        runTest {
            val fired = mutableListOf<String>()
            val monitor = DeadlineMonitor(this, { it() }) { fired += it }

            monitor.arm("old", 1_000)
            monitor.arm("new", 500)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf("new"), fired)
        }

    @Test
    fun `schedule boundary cancellation prevents callback`() =
        runTest {
            val fired = mutableListOf<String>()
            val monitor = DeadlineMonitor(this, { it() }) { fired += it }

            monitor.arm("com.example.app", 1_000)
            monitor.cancel()
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(fired.isEmpty())
        }

    @Test
    fun `cancelling after the deadline fired but before the lock is free prevents callback`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val fired = mutableListOf<String>()
            val monitor = DeadlineMonitor(this, ConfigurationChanges::serialized) { fired += it }
            val transition =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    ConfigurationChanges.serialized { release.await() }
                }
            try {
                monitor.arm("com.example.app", 1_000)
                advanceTimeBy(1_000)
                runCurrent()
                monitor.cancel()
            } finally {
                release.complete(Unit)
            }
            transition.join()
            runCurrent()

            assertTrue(fired.isEmpty())
        }
}
