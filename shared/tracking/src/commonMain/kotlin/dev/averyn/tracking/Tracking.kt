package dev.averyn.tracking

import dev.averyn.domain.LocationSample
import kotlinx.coroutines.flow.Flow

/**
 * Implemented natively per platform (Core Location / Fused Location Provider).
 * The native side converts platform callbacks into [LocationSample]s; everything downstream is shared.
 */
interface LocationSource {
    fun samples(): Flow<LocationSample>
}

/** Per-sample quality flags (TDD-0001 §5.4). A sample may carry several. Raw data is never dropped for these. */
enum class QualityFlag {
    POOR_ACCURACY,
    DUPLICATE,
    NON_MONOTONIC_TIME,
    SPEED_OUTLIER,
    JUMP,
    ALTITUDE_SPIKE,
}

/**
 * Durable, append-only local store. The engine writes a sample here *before* using it for live metrics (F3).
 * The concrete engine is an open decision (TDD-0001 Q2); the PoC compares implementations behind this interface.
 */
interface ActivityStore {
    suspend fun append(
        activityId: String,
        samples: List<LocationSample>,
    )

    suspend fun read(activityId: String): List<LocationSample>
}
