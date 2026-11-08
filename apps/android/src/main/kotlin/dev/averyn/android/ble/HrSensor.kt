package dev.averyn.android.ble

import dev.averyn.sensors.SensorAdapter

/** What the tracking service needs of a sensor: the shared adapter plus the count of values the model refused. */
interface HrSensor : SensorAdapter {
    /** A value of a frame the heart-rate model rejects: the caller counts it as a dropped input. */
    var onInvalid: (() -> Unit)?
}
