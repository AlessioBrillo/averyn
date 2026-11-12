package dev.averyn.tracking

/** A first fix worse than this (1σ radius, metres) does not mean "GPS is ready" (TDD-0001 section 5.2). */
const val FIRST_FIX_MAX_ACCURACY_M = 50.0

/** A first fix older than this is a cached position, not where the user stands now. */
const val FIRST_FIX_MAX_AGE_MS = 10_000L

/**
 * Whether a fix may move PREPARING to RECORDING. Both platforms use it, so the rule is one.
 *
 * Only the *transition* is gated: every fix is still stored as a raw sample (F3/F5) and judged by the quality
 * flags. [accuracyM] null (the platform reported none, or a negative value) is not a good fix.
 * ponytail: the thresholds are guesses until the campaign (S1) shows real cold-start fixes; tune from its data.
 */
fun isUsableFirstFix(
    accuracyM: Double?,
    ageMs: Long,
): Boolean = accuracyM != null && accuracyM <= FIRST_FIX_MAX_ACCURACY_M && ageMs <= FIRST_FIX_MAX_AGE_MS
