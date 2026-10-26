package com.androidpause.ui.config

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileActionGateTest {
    @Test
    fun rejectsRepeatedActionsSynchronouslyAndReleasesOnCancellation() =
        runTest {
            val gate = ProfileActionGate()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var calls = 0
            gate.launch(scope) {
                calls++
                awaitCancellation()
            }
            assertTrue(gate.busy)
            gate.launch(scope) { calls++ }
            runCurrent()
            assertEquals(1, calls)
            scope.cancel()
            runCurrent()
            assertFalse(gate.busy)
            gate.launch(this) { calls++ }
            runCurrent()
            assertEquals(2, calls)
            assertFalse(gate.busy)
        }

    @Test
    fun releasesOnFailureAndOnAlreadyCancelledScope() =
        runTest {
            val gate = ProfileActionGate()
            val failures = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, error -> failures += error },
                )
            gate.launch(scope) { error("write failed") }
            runCurrent()
            assertEquals("write failed", failures.single().message)
            assertFalse(gate.busy)
            scope.cancel()
            gate.launch(scope) { awaitCancellation() }
            runCurrent()
            assertFalse(gate.busy)
        }
}
