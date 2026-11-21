package dev.averyn.backend.social

import dev.averyn.api.FollowRequests
import dev.averyn.api.Relations
import dev.averyn.api.ReportRequest
import dev.averyn.api.SocialProfile
import dev.averyn.api.UserCard
import dev.averyn.api.UserRef
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Outstanding follow requests one account may have sent: a cap against a request spray. */
const val MAX_PENDING_SENT = 200

/** Open reports one account may have: a cap against a report spray. */
const val MAX_OPEN_REPORTS = 20

/** People in one list (followers, following, requests, blocks). ponytail: no cursor yet, add one past ~200 people. */
const val MAX_LIST = 200

sealed interface ProfileResult {
    data class Saved(
        val profile: SocialProfile,
    ) : ProfileResult

    data object HandleTaken : ProfileResult

    /** A handle changes once every 30 days; [until] is when the next change is allowed. */
    data class HandleTooSoon(
        val until: Instant,
    ) : ProfileResult
}

/**
 * Profiles, follows, blocks and reports (TDD-0018). It never reads the `activities` table: the activities of other
 * people come only from [ActivityAccess]. Blocking JDBC: call from `Dispatchers.IO`.
 */
class SocialStore(
    private val db: DataSource,
) {
    fun profile(owner: UUID): SocialProfile =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT handle, display_name, bio, sport, profile_private, default_visibility,
                        age_confirmed_at IS NOT NULL, avatar_key IS NOT NULL
                    FROM user_profiles WHERE owner_id = ?
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, owner)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            SocialProfile(id = owner.toString())
                        } else {
                            SocialProfile(
                                handle = rs.getString(1),
                                displayName = rs.getString(2),
                                bio = rs.getString(3),
                                sport = rs.getString(4),
                                profilePrivate = rs.getBoolean(5),
                                defaultVisibility = rs.getString(6),
                                ageConfirmed = rs.getBoolean(7),
                                hasAvatar = rs.getBoolean(8),
                                id = owner.toString(),
                            )
                        }
                    }
                }
        }

    /**
     * Saves the public side of the profile. A null `handle` keeps the current one (a handle is never removed by a save);
     * the first handle is free and so is the first change, after that one change per 30 days. The caller has checked
     * [SocialProfile.problem] and normalised the text.
     */
    fun putProfile(
        owner: UUID,
        p: SocialProfile,
    ): ProfileResult {
        db.connection.use { conn ->
            conn.autoCommit = false
            try {
                // The row exists from here on, so the lock below always has something to hold.
                conn
                    .prepareStatement(
                        "INSERT INTO user_profiles (owner_id, units) VALUES (?, 'metric') ON CONFLICT (owner_id) DO NOTHING",
                    ).use {
                        it.setObject(1, owner)
                        it.executeUpdate()
                    }
                var current: String? = null
                var changedAt: Instant? = null
                conn
                    .prepareStatement(
                        "SELECT handle, handle_changed_at FROM user_profiles WHERE owner_id = ? FOR UPDATE",
                    ).use { stmt ->
                        stmt.setObject(1, owner)
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            current = rs.getString(1)
                            changedAt = rs.getTimestamp(2)?.toInstant()
                        }
                    }
                val changing = p.handle != null && current != null && p.handle != current
                val last = changedAt
                if (changing && last != null) {
                    val until = last.plus(Duration.ofDays(SocialProfile.HANDLE_CHANGE_DAYS))
                    if (Instant.now().isBefore(until)) {
                        conn.rollback()
                        return ProfileResult.HandleTooSoon(until)
                    }
                }
                conn
                    .prepareStatement(
                        """
                        UPDATE user_profiles SET handle = coalesce(?, handle),
                            handle_changed_at = CASE WHEN ? THEN now() ELSE handle_changed_at END,
                            display_name = ?, bio = ?, sport = ?, profile_private = ?, default_visibility = ?,
                            age_confirmed_at = CASE WHEN ? THEN coalesce(age_confirmed_at, now()) ELSE age_confirmed_at END,
                            updated_at = now()
                        WHERE owner_id = ?
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, p.handle)
                        stmt.setBoolean(2, changing)
                        stmt.setString(3, p.displayName)
                        stmt.setString(4, p.bio)
                        stmt.setString(5, p.sport)
                        stmt.setBoolean(6, p.profilePrivate)
                        stmt.setString(7, p.defaultVisibility)
                        stmt.setBoolean(8, p.ageConfirmed)
                        stmt.setObject(9, owner)
                        stmt.executeUpdate()
                    }
                conn.commit()
            } catch (e: SQLException) {
                conn.rollback()
                if (e.sqlState == UNIQUE_VIOLATION) return ProfileResult.HandleTaken
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        return ProfileResult.Saved(profile(owner))
    }

    /** The key of [owner]'s avatar object, or null. */
    fun avatarKey(owner: UUID): String? =
        db.connection.use { conn ->
            conn.prepareStatement("SELECT avatar_key FROM user_profiles WHERE owner_id = ?").use { stmt ->
                stmt.setObject(1, owner)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** Sets (or clears, with null) the avatar key; returns the key it replaced so the caller can delete the object. */
    fun setAvatarKey(
        owner: UUID,
        key: String?,
    ): String? {
        val previous = avatarKey(owner)
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO user_profiles (owner_id, units, avatar_key) VALUES (?, 'metric', ?)
                    ON CONFLICT (owner_id) DO UPDATE SET avatar_key = EXCLUDED.avatar_key, updated_at = now()
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, owner)
                    stmt.setString(2, key)
                    stmt.executeUpdate()
                }
        }
        return previous
    }

    fun idByHandle(handle: String): UUID? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT p.owner_id FROM user_profiles p JOIN users u ON u.id = p.owner_id " +
                        "WHERE p.handle = ? AND u.deleted_at IS NULL",
                ).use { stmt ->
                    stmt.setString(1, handle)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                }
        }

    /** The card of [id] as [relation] allows: a private profile shows the name only to people who do not follow. */
    fun card(
        id: UUID,
        relation: Relation,
    ): UserCard {
        val p = profile(id)
        val restricted = !relation.self && !relation.following && (relation.profilePrivate || relation.blockedByViewer)
        return UserCard(
            id = id.toString(),
            handle = p.handle,
            displayName = p.displayName,
            bio = p.bio.takeUnless { restricted },
            sport = p.sport.takeUnless { restricted },
            hasAvatar = p.hasAvatar,
            relation = relationName(relation),
            restricted = restricted,
        )
    }

    // ---- follows ----

    /** True when [follower] already has [MAX_PENDING_SENT] requests waiting. */
    fun tooManyPending(follower: UUID): Boolean =
        count("SELECT count(*) FROM follows WHERE follower_id = ? AND state = 'pending'", follower) >= MAX_PENDING_SENT

    /** Sends a request; an existing follow or request stays as it is. */
    fun follow(
        follower: UUID,
        followed: UUID,
    ) = update(
        "INSERT INTO follows (follower_id, followed_id, state) VALUES (?, ?, 'pending') ON CONFLICT DO NOTHING",
        follower,
        followed,
    )

    /** Cancels a request or stops following: the same row. */
    fun unfollow(
        follower: UUID,
        followed: UUID,
    ) = update("DELETE FROM follows WHERE follower_id = ? AND followed_id = ?", follower, followed)

    /** The followed accepts a waiting request; false when there was none. */
    fun accept(
        followed: UUID,
        follower: UUID,
    ): Boolean =
        update(
            "UPDATE follows SET state = 'active' WHERE follower_id = ? AND followed_id = ? AND state = 'pending'",
            follower,
            followed,
        ) > 0

    /** The followed declines a waiting request; false when there was none. */
    fun decline(
        followed: UUID,
        follower: UUID,
    ): Boolean =
        update(
            "DELETE FROM follows WHERE follower_id = ? AND followed_id = ? AND state = 'pending'",
            follower,
            followed,
        ) >
            0

    /** The followed removes a follower, active or not. */
    fun removeFollower(
        followed: UUID,
        follower: UUID,
    ) = update("DELETE FROM follows WHERE follower_id = ? AND followed_id = ?", follower, followed)

    fun requests(owner: UUID): FollowRequests =
        FollowRequests(
            count =
                count(
                    "SELECT count(*) FROM follows f JOIN users u ON u.id = f.follower_id AND u.deleted_at IS NULL " +
                        "WHERE f.followed_id = ? AND f.state = 'pending'",
                    owner,
                ).toInt(),
            items =
                refs(
                    "SELECT u.id, p.handle, p.display_name, p.avatar_key IS NOT NULL FROM follows f " +
                        "JOIN users u ON u.id = f.follower_id AND u.deleted_at IS NULL " +
                        "LEFT JOIN user_profiles p ON p.owner_id = u.id " +
                        "WHERE f.followed_id = ? AND f.state = 'pending' ORDER BY f.created_at LIMIT $MAX_LIST",
                    owner,
                ),
        )

    /** The active followers of [owner], without people who are blocked either way with [viewer]. */
    fun followers(
        viewer: UUID,
        owner: UUID,
    ): List<UserRef> = people(viewer, owner, "f.follower_id", "f.followed_id")

    /** The people [owner] actively follows, without people who are blocked either way with [viewer]. */
    fun following(
        viewer: UUID,
        owner: UUID,
    ): List<UserRef> = people(viewer, owner, "f.followed_id", "f.follower_id")

    private fun people(
        viewer: UUID,
        owner: UUID,
        shown: String,
        anchor: String,
    ): List<UserRef> =
        refs(
            "SELECT u.id, p.handle, p.display_name, p.avatar_key IS NOT NULL FROM follows f " +
                "JOIN users u ON u.id = $shown AND u.deleted_at IS NULL " +
                "LEFT JOIN user_profiles p ON p.owner_id = u.id " +
                "WHERE $anchor = ? AND f.state = 'active' " +
                "AND NOT EXISTS (SELECT 1 FROM blocks b WHERE (b.blocker_id = ? AND b.blocked_id = u.id) " +
                "OR (b.blocker_id = u.id AND b.blocked_id = ?)) " +
                "ORDER BY f.created_at DESC LIMIT $MAX_LIST",
            owner,
            viewer,
            viewer,
        )

    // ---- blocks ----

    /** Blocks [blocked] and drops the follow in both directions (F7). */
    fun block(
        blocker: UUID,
        blocked: UUID,
    ) {
        db.connection.use { conn ->
            conn.autoCommit = false
            try {
                run(
                    conn,
                    "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    blocker,
                    blocked,
                )
                run(
                    conn,
                    "DELETE FROM follows WHERE (follower_id = ? AND followed_id = ?) OR (follower_id = ? AND followed_id = ?)",
                    blocker,
                    blocked,
                    blocked,
                    blocker,
                )
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun unblock(
        blocker: UUID,
        blocked: UUID,
    ) = update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", blocker, blocked)

    fun blocks(blocker: UUID): List<UserRef> =
        refs(
            "SELECT u.id, p.handle, p.display_name, p.avatar_key IS NOT NULL FROM blocks b " +
                "JOIN users u ON u.id = b.blocked_id AND u.deleted_at IS NULL " +
                "LEFT JOIN user_profiles p ON p.owner_id = u.id WHERE b.blocker_id = ? ORDER BY b.created_at DESC LIMIT $MAX_LIST",
            blocker,
        )

    // ---- reports ----

    fun tooManyReports(reporter: UUID): Boolean =
        count("SELECT count(*) FROM reports WHERE reporter_id = ? AND resolved_at IS NULL", reporter) >=
            MAX_OPEN_REPORTS

    /** Files a report; a second open report of the same target by the same reporter is the same report. */
    fun report(
        reporter: UUID,
        r: ReportRequest,
        targetUser: UUID,
        targetActivity: UUID?,
    ) = db.connection.use { conn ->
        conn
            .prepareStatement(
                "INSERT INTO reports (reporter_id, target_user_id, target_activity_id, reason, note) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            ).use { stmt ->
                stmt.setObject(1, reporter)
                stmt.setObject(2, targetUser)
                stmt.setObject(3, targetActivity)
                stmt.setString(4, r.reason)
                stmt.setString(5, r.note?.takeIf { it.isNotBlank() })
                stmt.executeUpdate()
            }
    }

    // ---- the visibility of the owner's own activity ----

    /**
     * Sets the visibility of the owner's activity. `link` gets [newToken] unless it already has one; any other value
     * clears the token, so an old link stops working at once. Returns the token (null unless `link`), or `NOT_FOUND`.
     */
    fun setVisibility(
        owner: UUID,
        id: UUID,
        visibility: String,
        newToken: String,
    ): Pair<Boolean, String?> =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE activities SET visibility = ?,
                        share_token = CASE WHEN ? = 'link' THEN coalesce(share_token, ?) ELSE NULL END
                    WHERE id = ? AND owner_id = ? RETURNING share_token
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, visibility)
                    stmt.setString(2, visibility)
                    stmt.setString(3, newToken)
                    stmt.setObject(4, id)
                    stmt.setObject(5, owner)
                    stmt.executeQuery().use { rs -> if (rs.next()) true to rs.getString(1) else false to null }
                }
        }

    // ---- export ----

    /** Everything social the user can ask for (GDPR art. 15/20): their public profile, graph, blocks and reports. */
    fun export(owner: UUID): SocialExport =
        SocialExport(
            profile = profile(owner),
            following = following(owner, owner),
            followers = followers(owner, owner),
            blocks = blocks(owner),
            reports =
                db.connection.use { conn ->
                    conn
                        .prepareStatement(
                            "SELECT target_user_id, target_activity_id, reason, note, created_at FROM reports " +
                                "WHERE reporter_id = ? ORDER BY created_at",
                        ).use { stmt ->
                            stmt.setObject(1, owner)
                            stmt.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(
                                            ReportRecord(
                                                targetUserId = rs.getString(1),
                                                targetActivityId = rs.getString(2),
                                                reason = rs.getString(3),
                                                note = rs.getString(4),
                                                createdAt = rs.getTimestamp(5).toInstant().toString(),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                },
        )

    // ---- plumbing ----

    private fun count(
        sql: String,
        id: UUID,
    ): Long =
        db.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
            }
        }

    private fun update(
        sql: String,
        vararg ids: UUID,
    ): Int = db.connection.use { conn -> run(conn, sql, *ids) }

    private fun run(
        conn: Connection,
        sql: String,
        vararg ids: UUID,
    ): Int =
        conn.prepareStatement(sql).use { stmt ->
            ids.forEachIndexed { i, id -> stmt.setObject(i + 1, id) }
            stmt.executeUpdate()
        }

    private fun refs(
        sql: String,
        vararg ids: UUID,
    ): List<UserRef> =
        db.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                ids.forEachIndexed { i, id -> stmt.setObject(i + 1, id) }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                UserRef(
                                    rs.getObject(1, UUID::class.java).toString(),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getBoolean(4),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}

/** The name of how the viewer stands to a person (`self`, `blocked`, `following`, `requested`, `none`). */
fun relationName(r: Relation): String =
    when {
        r.self -> Relations.SELF
        r.blockedByViewer -> Relations.BLOCKED
        r.following -> Relations.FOLLOWING
        r.followState == "pending" -> Relations.REQUESTED
        else -> Relations.NONE
    }

@Serializable
data class ReportRecord(
    val targetUserId: String,
    val targetActivityId: String?,
    val reason: String,
    val note: String?,
    val createdAt: String,
)

/** The `social.json` of the account export. */
@Serializable
data class SocialExport(
    val profile: SocialProfile,
    val following: List<UserRef>,
    val followers: List<UserRef>,
    val blocks: List<UserRef>,
    val reports: List<ReportRecord>,
)
