package dev.averyn.backend.profile

import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
import java.util.UUID
import javax.sql.DataSource

/** The profile of an account (TDD-0012). Blocking JDBC: call from `Dispatchers.IO`. */
class ProfileStore(
    private val db: DataSource,
) {
    /** The saved profile of [ownerId], or the defaults when the account never saved one. */
    fun get(ownerId: UUID): UserProfile =
        db.connection.use { conn ->
            conn.prepareStatement("SELECT units, max_hr_bpm FROM user_profiles WHERE owner_id = ?").use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        UserProfile()
                    } else {
                        UserProfile(
                            units = UnitSystem.valueOf(rs.getString(1).uppercase()),
                            maxHrBpm = rs.getInt(2).takeUnless { rs.wasNull() },
                        )
                    }
                }
            }
        }

    /** Replaces the profile of [ownerId]. The caller has checked [UserProfile.problem]. */
    fun put(
        ownerId: UUID,
        profile: UserProfile,
    ) = db.connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO user_profiles (owner_id, units, max_hr_bpm) VALUES (?, ?, ?)
                ON CONFLICT (owner_id) DO UPDATE
                    SET units = EXCLUDED.units, max_hr_bpm = EXCLUDED.max_hr_bpm, updated_at = now()
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setString(2, profile.units.name.lowercase())
                val maxHr = profile.maxHrBpm
                if (maxHr == null) stmt.setNull(3, java.sql.Types.SMALLINT) else stmt.setInt(3, maxHr)
                stmt.executeUpdate()
            }
    }
}
