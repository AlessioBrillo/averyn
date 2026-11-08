package dev.averyn.android.ble

import dev.averyn.sensors.SYNAPSE_FEATURE_CHARACTERISTIC_UUID
import dev.averyn.sensors.SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS
import dev.averyn.sensors.SYNAPSE_STATUS_CHARACTERISTIC_UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android allows one GATT operation at a time: a second descriptor or characteristic write issued before the
 * callback of the first is silently refused. The stepper runs the operations of a connection one after the other,
 * each until its callback (`onDescriptorWrite`, `onCharacteristicWrite`) reports through [done], or until it times
 * out. Pure Kotlin: no Android type, so the order and the timeout are tested on the JVM.
 *
 * A callback that arrives after its step timed out can complete the next step by mistake; the timeout is long
 * enough (seconds) that a real band answers well before it, and a step that fails is only a missed subscription.
 */
internal class GattStepper {
    private val lock = Mutex()

    @Volatile
    private var current: CompletableDeferred<Boolean>? = null

    /** Runs [start] (which issues the GATT call and returns whether it was accepted) and waits for its callback. */
    suspend fun step(
        timeoutMs: Long,
        start: () -> Boolean,
    ): Boolean =
        lock.withLock {
            val waiting = CompletableDeferred<Boolean>()
            current = waiting // before start(): the callback may come before start() returns
            val accepted = start()
            val ok = accepted && (withTimeoutOrNull(timeoutMs) { waiting.await() } ?: false)
            current = null
            ok
        }

    /** To be called from the GATT callback that ends the running step. */
    fun done(success: Boolean) {
        current?.complete(success)
    }
}

/**
 * The characteristics to subscribe to after connecting, in order (ADR-0030): the feature and status notifications
 * always (small, and the recorder decides what to write); the raw research streams only under `raw_signals`, since
 * they are the heaviest on the radio and the battery.
 */
internal fun synapseNotifyPlan(rawAllowed: Boolean): List<String> =
    buildList {
        add(SYNAPSE_FEATURE_CHARACTERISTIC_UUID)
        add(SYNAPSE_STATUS_CHARACTERISTIC_UUID)
        if (rawAllowed) addAll(SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.values)
    }

/** How long a connection attempt may take before it is counted as failed and retried with the backoff. */
internal const val GATT_CONNECT_TIMEOUT_MS = 20_000L

/**
 * The result of [this] within [timeoutMs], false when it did not come in time. A callback that never arrives (a
 * strap that went out of range between the scan and the connection, a stack that drops the event) would
 * otherwise leave the reconnect loop waiting for ever: the run would end with no heart rate and no error.
 */
internal suspend fun CompletableDeferred<Boolean>.awaitOrFalse(timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { await() } ?: false
