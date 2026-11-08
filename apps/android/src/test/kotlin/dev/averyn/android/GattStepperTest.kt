package dev.averyn.android

import dev.averyn.android.ble.GattStepper
import dev.averyn.android.ble.synapseNotifyPlan
import dev.averyn.sensors.SYNAPSE_FEATURE_CHARACTERISTIC_UUID
import dev.averyn.sensors.SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS
import dev.averyn.sensors.SYNAPSE_STATUS_CHARACTERISTIC_UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GattStepperTest {
    @Test
    fun aStepEndsWithItsCallback() =
        runBlocking {
            val stepper = GattStepper()
            val ok = async { stepper.step(2_000) { true } }
            delay(50)
            stepper.done(true)
            assertTrue(ok.await())
        }

    @Test
    fun aCallbackThatComesBeforeStartReturnsStillCounts() =
        runBlocking {
            val stepper = GattStepper()
            assertTrue(stepper.step(2_000) { stepper.done(true).let { true } })
        }

    @Test
    fun aRefusedCallOrAFailedCallbackOrATimeoutIsAFailedStep() =
        runBlocking {
            val stepper = GattStepper()
            assertFalse(stepper.step(2_000) { false }, "the stack refused the call")
            assertFalse(stepper.step(2_000) { stepper.done(false).let { true } }, "the callback said it failed")
            assertFalse(stepper.step(50) { true }, "no callback in time")
        }

    @Test
    fun stepsRunOneAtATimeInTheOrderTheyWereIssued() =
        runBlocking {
            val stepper = GattStepper()
            val order = mutableListOf<String>()
            val a =
                async {
                    stepper.step(2_000) {
                        order += "a-start"
                        true
                    }
                }
            delay(30)
            val b =
                async {
                    stepper.step(2_000) {
                        order += "b-start"
                        true
                    }
                }
            delay(30)
            assertEquals(listOf("a-start"), order, "b waits for a")
            stepper.done(true)
            a.await()
            delay(30)
            stepper.done(true)
            b.await()
            assertEquals(listOf("a-start", "b-start"), order)
        }

    @Test
    fun rawStreamsAreSubscribedOnlyUnderTheRawConsent() {
        val base = listOf(SYNAPSE_FEATURE_CHARACTERISTIC_UUID, SYNAPSE_STATUS_CHARACTERISTIC_UUID)
        assertEquals(base, synapseNotifyPlan(rawAllowed = false))
        assertEquals(base + SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.values, synapseNotifyPlan(rawAllowed = true))
    }
}
