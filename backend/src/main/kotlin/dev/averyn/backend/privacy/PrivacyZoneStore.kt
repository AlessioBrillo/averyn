package dev.averyn.backend.privacy

import dev.averyn.api.PrivacyZone
import dev.averyn.api.PrivacyZones
import kotlinx.serialization.json.Json
import java.security.MessageDigest
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

    /**
     * Replaces the zones of [ownerId] as a whole. The caller has checked [PrivacyZones.problem]. With [ifMatch] (the
     * [etagOf] the writer last read) the replacement happens only if the list is still that one: a phone that never saw
     * the zones another device saved must not wipe them. Returns false, and changes nothing, when it is not.
     */
    fun put(
        ownerId: UUID,
        zones: PrivacyZones,
        ifMatch: String? = null,
    ): Boolean =
        db.connection.use { conn ->
            conn.autoCommit = false
            try {
                // Two saves at once would both delete and then both insert the same positions: the second waits here.
                conn.prepareStatement("SELECT 1 FROM users WHERE id = ? FOR UPDATE").use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeQuery().use { it.next() }
                }
                if (ifMatch != null && etagOf(get(ownerId)) != ifMatch) {
                    conn.rollback()
                    return false
                }
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
                true
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }

    companion object {
        /** A quoted hash of the list as the API writes it: the validator of `GET` and the `If-Match` of `PUT`. */
        fun etagOf(zones: PrivacyZones): String {
            val canonical = Json.encodeToString(PrivacyZones.serializer(), zones)
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return "\"" + digest.joinToString("") { "%02x".format(it) }.take(32) + "\""
        }
    }
}
