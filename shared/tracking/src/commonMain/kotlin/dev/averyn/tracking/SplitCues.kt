package dev.averyn.tracking

import dev.averyn.metrics.ActivitySnapshot

/** One boundary of the distance (a kilometre or a mile) crossed while recording: what a voice cue says (S44). */
data class SplitCue(
    /** 1 for the first kilometre (or mile), 2 for the second... */
    val index: Int,
    /** The moving time of the whole activity at the boundary. */
    val totalMovingMs: Long,
    /** The moving time of this split alone. */
    val splitMovingMs: Long,
)

/**
 * The per-kilometre (or per-mile) events of the voice cues. The apps feed it the live [ActivitySnapshot] and speak
 * what it returns; the text-to-speech is theirs, the arithmetic is here, so both phones say the same thing.
 *
 * The distance can jump over a boundary between two updates (a gap in the signal), and then over several: one cue
 * is returned for each, in order, with the time of the crossing interpolated between the two snapshots. A distance
 * that goes back (it never should) crosses nothing. Starting at [startDistanceM] makes a recovered activity not
 * repeat the boundaries it already passed.
 *
 * [unitM] is 1000 for kilometres and 1609.344 for miles, from the profile of the user.
 */
class SplitCueTracker(
    private val unitM: Double,
    startDistanceM: Double = 0.0,
    startMovingMs: Long = 0,
) {
    init {
        require(unitM > 0) { "unitM must be positive: $unitM" }
    }

    private var lastDistanceM = startDistanceM
    private var lastMovingMs = startMovingMs
    private var nextIndex = (startDistanceM / unitM).toInt() + 1
    private var lastCueMovingMs = startMovingMs

    fun onSnapshot(snapshot: ActivitySnapshot): List<SplitCue> {
        val distanceM = snapshot.distanceM
        val movingMs = snapshot.movingMs
        if (distanceM <= lastDistanceM) {
            lastMovingMs = maxOf(lastMovingMs, movingMs)
            return emptyList()
        }
        val cues = mutableListOf<SplitCue>()
        while (nextIndex * unitM <= distanceM) {
            val boundaryM = nextIndex * unitM
            val share = (boundaryM - lastDistanceM) / (distanceM - lastDistanceM)
            val crossingMs = lastMovingMs + ((movingMs - lastMovingMs) * share).toLong()
            cues += SplitCue(nextIndex, crossingMs, crossingMs - lastCueMovingMs)
            lastCueMovingMs = crossingMs
            nextIndex++
        }
        lastDistanceM = distanceM
        lastMovingMs = movingMs
        return cues
    }
}
