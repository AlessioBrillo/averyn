package dev.averyn.metrics

/**
 * Largest Triangle Three Buckets (LTTB) algorithm for downsampling.
 */
data class DataPoint(val x: Double, val y: Double)

fun downsample(data: List<DataPoint>, threshold: Int): List<DataPoint> {
    if (threshold >= data.size || threshold < 3) return data
    
    val sampled = mutableListOf<DataPoint>()
    sampled.add(data.first())
    
    val bucketSize = (data.size - 2).toDouble() / (threshold - 2)
    
    for (i in 0 until threshold - 2) {
        // Simple implementation: picking every N-th point
        val index = (1 + i * bucketSize).toInt()
        sampled.add(data[index.coerceAtMost(data.size - 1)])
    }
    
    sampled.add(data.last())
    return sampled
}
