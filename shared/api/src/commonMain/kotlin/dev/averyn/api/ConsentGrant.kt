package dev.averyn.api

import kotlinx.serialization.Serializable

/**
 * One consent grant held by the server for the caller (ADR-0021).
 *
 * `revokedAt` is null when the grant is active; non-null means revoked. The device never decides on its
 * own whether it may record: it asks the server, caches the answer, and fails closed.
 */
@Serializable
data class ConsentGrant(
    /** The consent scope, e.g. `rr_recording`. */
    val scope: String,
    /** ISO-8601 instant when the grant was created (UTC). */
    val grantedAt: String,
    /** ISO-8601 instant when the grant was revoked, or null when still active. */
    val revokedAt: String?,
)
