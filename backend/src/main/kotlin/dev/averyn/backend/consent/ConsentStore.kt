package dev.averyn.backend.consent

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** One consent grant: the scope, when it was granted, and (if revoked) when. */
data class ConsentGrant(
    val scope: String,
    val grantedAt: Instant,
    val revokedAt: Instant?,
)

/**
 * Server-authoritative consent store (ADR-0021): the row is the audit record of grant/revoke and survives
 * revocation; only revoked_at is set. Data covered by a revoked scope is deleted by the caller (RR sidecar
 * objects + deleted_rr tombstone), not here. Blocking JDBC: call from [Dispatchers.IO].
 */
class ConsentStore(
    private val db: DataSource,
) {
    /** Every grant for [ownerId], revoked or not. */
    fun list(ownerId: UUID): List<ConsentGrant> =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT scope, granted_at, revoked_at FROM consents WHERE owner_id = ? ORDER BY scope",
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ConsentGrant(
                                        scope = rs.getString(1),
                                        grantedAt = rs.getTimestamp(2).toInstant(),
                                        revokedAt =
                                            run {
                                                val ts = rs.getTimestamp(3)
                                                if (ts == null) null else ts.toInstant()
                                            },
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    /** Grants [scope] for [ownerId] now (idempotent: re-granting clears a previous revocation). */
    fun grant(
        ownerId: UUID,
        scope: String,
        at: Instant = Instant.now(),
    ) = db.connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO consents (owner_id, scope, granted_at, revoked_at) VALUES (?, ?, ?, NULL)
                ON CONFLICT (owner_id, scope) DO UPDATE SET granted_at = EXCLUDED.granted_at, revoked_at = NULL
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setString(2, scope)
                stmt.setTimestamp(3, java.sql.Timestamp.from(at))
                stmt.executeUpdate()
            }
    }

    /** Revokes [scope] for [ownerId] now. Idempotent: revoking an already-revoked or unknown scope is a no-op. */
    fun revoke(
        ownerId: UUID,
        scope: String,
        at: Instant = Instant.now(),
    ) = db.connection.use { conn ->
        conn
            .prepareStatement(
                """
                UPDATE consents SET revoked_at = ? WHERE owner_id = ? AND scope = ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { stmt ->
                stmt.setTimestamp(1, java.sql.Timestamp.from(at))
                stmt.setObject(2, ownerId)
                stmt.setString(3, scope)
                stmt.executeUpdate()
            }
    }

    /** True when [scope] is currently granted for [ownerId] (a row exists and is not revoked). */
    fun isGranted(
        ownerId: UUID,
        scope: String,
    ): Boolean =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT 1 FROM consents WHERE owner_id = ? AND scope = ? AND revoked_at IS NULL",
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setString(2, scope)
                    stmt.executeQuery().use { it.next() }
                }
        }

    /** Runs [block] inside a transaction. */
    internal fun inTransaction(block: (Connection) -> Unit) {
        db.connection.use { conn ->
            conn.autoCommit = false
            try {
                block(conn)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
}
