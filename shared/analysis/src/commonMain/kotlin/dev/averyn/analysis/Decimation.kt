package dev.averyn.analysis

import kotlin.math.abs

/**
 * The largest triangle three buckets decimation: it keeps the points that carry the shape of the curve and
 * drops the ones that only repeat it. The chart shows a bounded number of points, the numbers do not change.
 *
 * @return the indexes of the kept points, in order, the first and the last always among them.
 */
internal fun decimateLttb(
    xs: DoubleArray,
    ys: DoubleArray,
    threshold: Int,
): IntArray {
    val n = xs.size
    require(xs.size == ys.size) { "the two series must have the same length" }
    if (threshold >= n || threshold < 3) return IntArray(n) { it }
    val kept = IntArray(threshold)
    val every = (n - 2).toDouble() / (threshold - 2)
    var anchor = 0
    kept[0] = 0
    for (i in 0 until threshold - 2) {
        // The average of the next bucket is the point the triangle is built against.
        var avgX = 0.0
        var avgY = 0.0
        var avgCount = 0
        val avgStart = (((i + 1) * every).toInt() + 1).coerceAtMost(n - 1)
        val avgEnd = (((i + 2) * every).toInt() + 1).coerceAtMost(n)
        for (j in avgStart until avgEnd) {
            avgX += xs[j]
            avgY += ys[j]
            avgCount++
        }
        if (avgCount > 0) {
            avgX /= avgCount
            avgY /= avgCount
        }
        // The bucket of the points that may be picked.
        val rangeStart = ((i * every).toInt() + 1).coerceAtMost(n - 1)
        val rangeEnd = (((i + 1) * every).toInt() + 1).coerceAtMost(n)
        var maxArea = -1.0
        var maxIndex = rangeStart
        for (j in rangeStart until rangeEnd) {
            val area =
                abs(
                    (xs[anchor] - avgX) * (ys[j] - ys[anchor]) -
                        (xs[anchor] - xs[j]) * (avgY - ys[anchor]),
                ) * 0.5
            if (area > maxArea) {
                maxArea = area
                maxIndex = j
            }
        }
        kept[i + 1] = maxIndex
        anchor = maxIndex
    }
    kept[threshold - 1] = n - 1
    return kept
}
