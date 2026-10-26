package com.androidpause.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationChangesTest {
    @Test
    fun transitionsMutationsAndTeardownShareOneQueueAndSkipCancelledWaiters() =
        runTest {
            val previousHook = ConfigurationChanges.beforeChange
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            try {
                ConfigurationChanges.beforeChange = { order += "flush before write" }
                val transition =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        ConfigurationChanges.serialized {
                            order += "transition starts"
                            release.await()
                            order += "transition ends"
                        }
                    }
                val mutation =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        ConfigurationChanges.mutate { order += "configuration write" }
                    }
                val cancelled =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        ConfigurationChanges.serialized { error("Cancelled callback must not run") }
                    }
                cancelled.cancel()
                cancelled.join()
                val teardown =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        ConfigurationChanges.serialized { order += "teardown flush" }
                    }
                assertEquals(listOf("transition starts"), order)
                release.complete(Unit)
                transition.join()
                mutation.join()
                teardown.join()
                assertEquals(
                    listOf(
                        "transition starts",
                        "transition ends",
                        "flush before write",
                        "configuration write",
                        "teardown flush",
                    ),
                    order,
                )
                assertEquals("available", ConfigurationChanges.serialized { "available" })
            } finally {
                release.complete(Unit)
                ConfigurationChanges.beforeChange = previousHook
            }
        }

    @Test
    fun failedBeforeChangeEmitsRecoveryWithoutRunningWrite() =
        runTest {
            val previousHook = ConfigurationChanges.beforeChange
            val failure = IllegalStateException("Accounting flush failed")
            var writes = 0
            var events = 0
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    ConfigurationChanges.events.collect { events++ }
                }
            try {
                ConfigurationChanges.beforeChange = { throw failure }
                val result = runCatching { ConfigurationChanges.mutate { writes++ } }
                runCurrent()

                assertSame(failure, result.exceptionOrNull())
                assertEquals(0, writes)
                assertEquals(1, events)

                ConfigurationChanges.beforeChange = null
                assertEquals("saved", ConfigurationChanges.mutate { "saved" })
                runCurrent()
                assertEquals(2, events)
            } finally {
                ConfigurationChanges.beforeChange = previousHook
                collector.cancel()
            }
        }

    @Test
    fun canceledBeforeChangeEmitsRecoveryWithoutRunningWrite() =
        runTest {
            val previousHook = ConfigurationChanges.beforeChange
            val hookEntered = CompletableDeferred<Unit>()
            var writes = 0
            var events = 0
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    ConfigurationChanges.events.collect { events++ }
                }
            try {
                ConfigurationChanges.beforeChange = {
                    hookEntered.complete(Unit)
                    awaitCancellation()
                }
                val mutation = launch { ConfigurationChanges.mutate { writes++ } }
                hookEntered.await()
                mutation.cancel()
                mutation.join()
                runCurrent()

                assertTrue(mutation.isCancelled)
                assertEquals(0, writes)
                assertEquals(1, events)

                ConfigurationChanges.beforeChange = null
                assertEquals("saved", ConfigurationChanges.mutate { "saved" })
                runCurrent()
                assertEquals(2, events)
            } finally {
                ConfigurationChanges.beforeChange = previousHook
                collector.cancel()
            }
        }

    @Test
    fun failedWriteEmitsRecoveryAndLaterWriteSucceeds() =
        runTest {
            val previousHook = ConfigurationChanges.beforeChange
            val failure = IllegalStateException("Write failed")
            var hookRan = false
            var events = 0
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    ConfigurationChanges.events.collect { events++ }
                }
            try {
                ConfigurationChanges.beforeChange = { hookRan = true }
                val result =
                    runCatching {
                        ConfigurationChanges.mutate {
                            assertTrue(hookRan)
                            throw failure
                        }
                    }
                runCurrent()
                assertSame(failure, result.exceptionOrNull())
                assertEquals(1, events)

                assertEquals(42, ConfigurationChanges.mutate { 42 })
                runCurrent()
                assertEquals(2, events)
            } finally {
                ConfigurationChanges.beforeChange = previousHook
                collector.cancel()
            }
        }
}
