package dev.averyn.backend.auth

import io.ktor.server.auth.jwt.JWTPrincipal
import java.util.UUID
import javax.sql.DataSource

/**
 * The Averyn user for an authenticated token: created on first sight of (issuer, subject), then looked up.
 * Blocking JDBC: call from `Dispatchers.IO`.
 *
 * ponytail: one round trip per request. Cache (issuer, subject) -> id if profiling ever shows it.
 */
fun DataSource.userIdFor(principal: JWTPrincipal): UUID =
    connection.use { conn ->
        // DO UPDATE (a no-op assignment) so RETURNING yields the row on both insert and conflict.
        conn
            .prepareStatement(
                """
                INSERT INTO users (oidc_issuer, oidc_subject) VALUES (?, ?)
                ON CONFLICT (oidc_issuer, oidc_subject) DO UPDATE SET oidc_subject = EXCLUDED.oidc_subject
                RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, requireNotNull(principal.payload.issuer))
                stmt.setString(2, requireNotNull(principal.payload.subject))
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getObject(1, UUID::class.java)
                }
            }
    }
