package dev.averyn.backend.auth

import io.ktor.server.auth.jwt.JWTPrincipal
import java.util.UUID
import javax.sql.DataSource

/**
 * The Averyn user for an authenticated token: created on first sight of (issuer, subject), then looked up.
 * Known users are only read (no row rewrite per request); a concurrent first sight is settled by the unique key.
 * Blocking JDBC: call from `Dispatchers.IO`.
 *
 * ponytail: one round trip per request. Cache (issuer, subject) -> id if profiling ever shows it.
 */
fun DataSource.userIdFor(principal: JWTPrincipal): UUID {
    val issuer = requireNotNull(principal.payload.issuer)
    val subject = requireNotNull(principal.payload.subject)
    return connection.use { conn ->
        val known =
            conn.prepareStatement("SELECT id FROM users WHERE oidc_issuer = ? AND oidc_subject = ?").use { stmt ->
                stmt.setString(1, issuer)
                stmt.setString(2, subject)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            }
        known ?: conn
            .prepareStatement(
                """
                INSERT INTO users (oidc_issuer, oidc_subject) VALUES (?, ?)
                ON CONFLICT (oidc_issuer, oidc_subject) DO UPDATE SET oidc_subject = EXCLUDED.oidc_subject
                RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, issuer)
                stmt.setString(2, subject)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getObject(1, UUID::class.java)
                }
            }
    }
}
