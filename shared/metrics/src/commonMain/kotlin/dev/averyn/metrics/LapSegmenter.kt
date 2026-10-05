package dev.averyn.metrics

import dev.averyn.domain.LocationSample

data class Lap(val number: Int, val distanceM: Double, val durationMs: Long)

class LapSegmenter(private val lapDistanceM: Double = 1000.0) {
    private var currentLapDistance = 0.0
    private var lastSample: LocationSample? = null
    private val laps = mutableListOf<Lap>()
    private var lapNumber = 1

    fun onSample(sample: LocationSample, distanceSinceLast: Double) {
        currentLapDistance += distanceSinceLast
        if (currentLapDistance >= lapDistanceM) {
            laps.add(Lap(lapNumber++, lapDistanceM, sample.elapsedRealtimeMs))
            currentLapDistance -= lapDistanceM
        }
        lastSample = sample
    }
    
    fun getLaps(): List<Lap> = laps
}
