package dev.averyn.backend.social

import dev.averyn.api.PublicActivity
import dev.averyn.api.PublicActivityPage
import dev.averyn.api.Visibilities
import dev.averyn.backend.activities.ActivityCursor
import dev.averyn.backend.activities.ActivityIngest
import dev.averyn.backend.privacy.PrivacyZoneStore
import dev.averyn.backend.privacy.hideZonesIn
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** How a viewer stands to an owner (TDD-0018 F3). [blockedByViewer] and [blockedViewer] are two different facts. */
data class Relation(
    val self: Boolean,
    /** `pending`, `active` or null: the viewer's follow of the owner. */
    val followState: String?,
    val blockedByViewer: Boolean,
    val blockedViewer: Boolean,
    val profilePrivate: Boolean,
) {
    val following get() = followState == "active"
    val blocked get() = blockedByViewer || blockedViewer

    /**
     * THE visibility rule (TDD-0018 F3, ADR-0036): the visibilities of an owner's activities that this viewer may see.
     * Every read of someone else's activity asks this and nothing else; `link` is never listed here, it opens only
     * through its token ([ActivityAccess.shared]).
     */
    fun visibleVisibilities(): Set<String> =
        when {
            self -> Visibilities.ALL
            blocked -> emptySet()
            following -> setOf(Visibilities.FOLLOWERS, Visibilities.PUBLIC)
            profilePrivate -> emptySet()
            else -> setOf(Visibilities.PUBLIC)
        }
}

/**
 * The only door to an activity for anyone but its owner. It holds the only SQL of the social package that reads the
 * `activities` table (a test enforces it), and the only place that returns a non-owner track, which always has the
 * owner's privacy zones cut out. A summary here has no health field at all ([PublicActivity]).
 * Blocking JDBC: call from `Dispatchers.IO`.
 */
class ActivityAccess(
    private val db: DataSource,
    private val ingest: ActivityIngest,
    private val zones: PrivacyZoneStore,
) {
    /** The viewer's relation to [owner], or null when the owner does not exist or deleted the account. */
    fun relation(
        viewer: UUID,
        owner: UUID,
    ): Relation? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT coalesce(p.profile_private, false),
                        (SELECT state FROM follows WHERE follower_id = ?::uuid AND followed_id = u.id),
                        EXISTS (SELECT 1 FROM blocks WHERE blocker_id = ?::uuid AND blocked_id = u.id),
                        EXISTS (SELECT 1 FROM blocks WHERE blocker_id = u.id AND blocked_id = ?::uuid)
                    FROM users u LEFT JOIN user_profiles p ON p.owner_id = u.id
                    WHERE u.id = ?::uuid AND u.deleted_at IS NULL
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, viewer)
                    stmt.setObject(2, viewer)
                    stmt.setObject(3, viewer)
                    stmt.setObject(4, owner)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            Relation(
                                self = viewer == owner,
                                followState = rs.getString(2),
                                blockedByViewer = rs.getBoolean(3),
                                blockedViewer = rs.getBoolean(4),
                                profilePrivate = rs.getBoolean(1),
                            )
                        }
                    }
                }
        }

    /** A page of what [viewer] may see of [owner]'s activities; null when the owner is not there for the viewer. */
    fun page(
        viewer: UUID,
        owner: UUID,
        limit: Int,
        after: ActivityCursor?,
    ): PublicActivityPage? {
        val relation = relation(viewer, owner)?.takeUnless { !it.self && it.blocked } ?: return null
        val allowed = relation.visibleVisibilities()
        if (allowed.isEmpty()) return PublicActivityPage(emptyList(), null)
        val rows =
            db.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        SELECT $PUBLIC_COLUMNS FROM activities
                        WHERE owner_id = ? AND visibility = ANY (?)
                            AND (?::timestamptz IS NULL OR (started_at, id) < (?::timestamptz, ?::uuid))
                        ORDER BY started_at DESC, id DESC LIMIT ?
                        """.trimIndent(),
                    ).use { stmt ->
                        val cursorTime = after?.let { Timestamp.from(it.startedAt) }
                        stmt.setObject(1, owner)
                        stmt.setArray(2, conn.createArrayOf("text", allowed.toTypedArray()))
                        stmt.setTimestamp(3, cursorTime)
                        stmt.setTimestamp(4, cursorTime)
                        stmt.setObject(5, after?.id)
                        stmt.setInt(6, limit + 1)
                        stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPublic()) } }
                    }
            }
        val items = rows.take(limit)
        val next =
            if (rows.size > limit) {
                items.last().let { ActivityCursor(Instant.parse(it.startedAt), UUID.fromString(it.id)).toString() }
            } else {
                null
            }
        return PublicActivityPage(items, next)
    }

    /** One activity of [owner] as [viewer] may see it, or null: not visible and not existing look the same. */
    fun activity(
        viewer: UUID,
        owner: UUID,
        id: UUID,
    ): PublicActivity? {
        val relation = relation(viewer, owner) ?: return null
        val allowed = relation.visibleVisibilities()
        if (allowed.isEmpty()) return null
        return db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT $PUBLIC_COLUMNS FROM activities WHERE id = ? AND owner_id = ? AND visibility = ANY (?)",
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, owner)
                    stmt.setArray(3, conn.createArrayOf("text", allowed.toTypedArray()))
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toPublic() else null }
                }
        }
    }

    /** The track of an activity [viewer] may see, with the owner's zones cut out; null when it is not visible. */
    fun track(
        viewer: UUID,
        owner: UUID,
        id: UUID,
    ): String? {
        if (activity(viewer, owner, id) == null) return null
        return cut(owner, id)
    }

    /** The activity behind a share token (visibility `link`, owner still there), or null. */
    fun shared(token: String): PublicActivity? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT a.id, a.owner_id, a.sport, a.started_at, a.distance_m, a.elapsed_ms, a.moving_ms,
                        a.average_speed_mps, a.pace_sec_per_km
                    FROM activities a JOIN users u ON u.id = a.owner_id
                    WHERE a.share_token = ? AND a.visibility = 'link' AND u.deleted_at IS NULL
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, token)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toPublic() else null }
                }
        }

    /** The track behind a share token, zones cut out; null when the token opens nothing. */
    fun sharedTrack(token: String): String? {
        val activity = shared(token) ?: return null
        return cut(UUID.fromString(activity.ownerId), UUID.fromString(activity.id))
    }

    private fun cut(
        owner: UUID,
        id: UUID,
    ): String? = ingest.trackGeoJson(owner, id)?.let { hideZonesIn(it, zones.get(owner)) }

    private companion object {
        const val PUBLIC_COLUMNS =
            "id, owner_id, sport, started_at, distance_m, elapsed_ms, moving_ms, average_speed_mps, pace_sec_per_km"
    }
}

private fun ResultSet.toPublic(): PublicActivity =
    PublicActivity(
        id = getObject(1, UUID::class.java).toString(),
        ownerId = getObject(2, UUID::class.java).toString(),
        sport = getString(3),
        startedAt = getTimestamp(4).toInstant().toString(),
        distanceM = getDouble(5),
        elapsedMs = getLong(6),
        movingMs = getLong(7),
        averageSpeedMps = getDouble(8),
        paceSecPerKm = getDouble(9).takeUnless { wasNull() },
    )
