package dev.averyn.backend.privacy

import dev.averyn.api.PrivacyZone
import dev.averyn.api.PrivacyZones
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/** The privacy zones of an account (TDD-0016). Blocking JDBC: call from `Dispatchers.IO`. */
class PrivacyZoneStore(
    private val db: DataSource,
) {
    /** The zones of [ownerId], in the order the user gave them; none when the account never saved any. */
    fun get(ownerId: UUID): PrivacyZones =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT latitude, longitude, radius_m, label FROM privacy_zones WHERE owner_id = ? ORDER BY position",
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeQuery().use { rs ->
                        val zones = mutableListOf<PrivacyZone>()
                        while (rs.next()) {
                            zones += PrivacyZone(rs.getDouble(1), rs.getDouble(2), rs.getInt(3), rs.getString(4))
                        }
                        PrivacyZones(zones)
                    }
                }
        }

    /** Replaces the zones of [ownerId] as a whole. The caller has checked [PrivacyZones.problem]. */
    fun put(
        ownerId: UUID,
        zones: PrivacyZones,
    ) = db.connection.use { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM privacy_zones WHERE owner_id = ?").use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.executeUpdate()
            }
            val insert =
                "INSERT INTO privacy_zones (owner_id, position, latitude, longitude, radius_m, label) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            conn.prepareStatement(insert).use { stmt ->
                zones.zones.forEachIndexed { index, zone ->
                    stmt.setObject(1, ownerId)
                    stmt.setInt(2, index)
                    stmt.setDouble(3, zone.latitude)
                    stmt.setDouble(4, zone.longitude)
                    stmt.setInt(5, zone.radiusM)
                    if (zone.label == null) stmt.setNull(6, Types.VARCHAR) else stmt.setString(6, zone.label)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }
}
