package dev.averyn.backend.synapse

import dev.averyn.backend.activities.UserData
import dev.averyn.backend.consent.ConsentScope
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.sensors.SYNAPSE_FEATURE_FRAME_BYTES
import dev.averyn.sensors.SYNAPSE_STATUS_BYTES
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource

/** The two kinds of sidecar and the consent each one needs (ADR-0030). */
enum class SynapseKind(
    val wireName: String,
    val scope: String,
) {
    FEATURES("features", ConsentScope.SIGNAL_QUALITY),
    RAW("raw", ConsentScope.RAW_SIGNALS),
    ;

    companion object {
        fun fromWire(name: String?): SynapseKind? = entries.firstOrNull { it.wireName == name }
    }
}

/** A stored sidecar. */
class StoredSidecar(
    val objectKey: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * The sidecars of Synapse recordings: where they are, and what happens to them when a consent is revoked
 * (ADR-0021: revoking really deletes the data it covers).
 */
class SynapseStore(
    private val db: DataSource,
    private val raw: RawObjectStore,
) {
    fun keyOf(
        owner: UUID,
        clientActivityId: UUID,
        kind: SynapseKind,
        sha256: String,
    ) = "raw/$owner/$clientActivityId/synapse/${kind.wireName}/$sha256.bin"

    fun activityId(
        owner: UUID,
        clientActivityId: UUID,
    ): UUID? =
        db.connection.use { conn ->
            conn.prepareStatement("SELECT id FROM activities WHERE owner_id = ? AND client_activity_id = ?").use { s ->
                s.setObject(1, owner)
                s.setObject(2, clientActivityId)
                s.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            }
        }

    fun get(
        owner: UUID,
        clientActivityId: UUID,
        kind: SynapseKind,
    ): StoredSidecar? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT s.object_key, s.sha256, s.size_bytes FROM activity_synapse_sidecars s " +
                        "JOIN activities a ON a.id = s.activity_id " +
                        "WHERE a.owner_id = ? AND a.client_activity_id = ? AND s.kind = ?",
                ).use { s ->
                    s.setObject(1, owner)
                    s.setObject(2, clientActivityId)
                    s.setString(3, kind.wireName)
                    s.executeQuery().use { rs ->
                        if (rs.next()) StoredSidecar(rs.getString(1), rs.getString(2).trim(), rs.getLong(3)) else null
                    }
                }
        }

    fun deleted(
        owner: UUID,
        clientActivityId: UUID,
        kind: SynapseKind,
    ): Boolean =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT 1 FROM deleted_synapse WHERE owner_id = ? AND client_activity_id = ? AND kind = ?",
                ).use { s ->
                    s.setObject(1, owner)
                    s.setObject(2, clientActivityId)
                    s.setString(3, kind.wireName)
                    s.executeQuery().use { rs -> rs.next() }
                }
        }

    /** Records (or replaces) the sidecar of an activity. False when the activity is not the owner's. */
    fun record(
        owner: UUID,
        clientActivityId: UUID,
        kind: SynapseKind,
        sidecar: StoredSidecar,
    ): Boolean {
        val activity = activityId(owner, clientActivityId) ?: return false
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO activity_synapse_sidecars " +
                        "(activity_id, kind, owner_id, object_key, sha256, size_bytes) " +
                        "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (activity_id, kind) DO UPDATE SET " +
                        "object_key = EXCLUDED.object_key, sha256 = EXCLUDED.sha256, " +
                        "size_bytes = EXCLUDED.size_bytes, " +
                        "created_at = now()",
                ).use { s ->
                    s.setObject(1, activity)
                    s.setString(2, kind.wireName)
                    s.setObject(3, owner)
                    s.setString(4, sidecar.objectKey)
                    s.setString(5, sidecar.sha256)
                    s.setLong(6, sidecar.sizeBytes)
                    s.executeUpdate()
                }
        }
        return true
    }

    /** Everything the owner has stored (activities, their sources, RR and Synapse sidecars), for the quota. */
    fun storedBytes(owner: UUID): Long =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT (SELECT coalesce(sum(size_bytes), 0) " +
                        "FROM activity_synapse_sidecars WHERE owner_id = ?) + " +
                        "(SELECT coalesce(sum(raw_size_bytes + coalesce(source_size_bytes, 0) " +
                        "+ coalesce(rr_size_bytes, 0)), 0) " +
                        "FROM activities WHERE owner_id = ?)",
                ).use { s ->
                    s.setObject(1, owner)
                    s.setObject(2, owner)
                    s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                }
        }

    /** Deletes every sidecar of [kind] the owner has and leaves a tombstone for each activity (ADR-0021). */
    fun deleteKind(
        owner: UUID,
        kind: SynapseKind,
    ): Int {
        data class Row(
            val clientActivityId: UUID,
            val key: String,
        )
        val rows =
            db.connection.use { conn ->
                conn
                    .prepareStatement(
                        "SELECT a.client_activity_id, s.object_key FROM activity_synapse_sidecars s " +
                            "JOIN activities a ON a.id = s.activity_id WHERE s.owner_id = ? AND s.kind = ?",
                    ).use { s ->
                        s.setObject(1, owner)
                        s.setString(2, kind.wireName)
                        s.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(Row(rs.getObject(1, UUID::class.java), rs.getString(2))) }
                        }
                    }
            }
        db.connection.use { conn ->
            conn.autoCommit = false
            try {
                val delete = "DELETE FROM activity_synapse_sidecars WHERE owner_id = ? AND kind = ?"
                conn.prepareStatement(delete).use { s ->
                    s.setObject(1, owner)
                    s.setString(2, kind.wireName)
                    s.executeUpdate()
                }
                conn
                    .prepareStatement(
                        "INSERT INTO deleted_synapse (owner_id, client_activity_id, kind) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    ).use { s ->
                        rows.forEach {
                            s.setObject(1, owner)
                            s.setObject(2, it.clientActivityId)
                            s.setString(3, kind.wireName)
                            s.addBatch()
                        }
                        s.executeBatch()
                    }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
        // After the commit: an object left behind by a crash is an orphan under the activity's prefix, which the
        // activity's and the account's deletion remove; the reverse order left rows pointing at objects that were gone.
        rows.forEach { raw.delete(it.key) }
        return rows.size
    }

    /**
     * Rewrites the owner's feature sidecars with [fields] set to "not available", for a consent that covers only some
     * fields of a frame (stress, RR, the band's HRV): the sidecar stays, what the consent covered is gone. Returns how
     * many sidecars were rewritten.
     */
    fun maskFeatures(
        owner: UUID,
        fields: Set<FeatureField>,
    ): Int {
        data class Row(
            val clientActivityId: UUID,
            val key: String,
        )
        val rows =
            db.connection.use { conn ->
                conn
                    .prepareStatement(
                        "SELECT a.client_activity_id, s.object_key FROM activity_synapse_sidecars s " +
                            "JOIN activities a ON a.id = s.activity_id WHERE s.owner_id = ? AND s.kind = 'features'",
                    ).use { s ->
                        s.setObject(1, owner)
                        s.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(Row(rs.getObject(1, UUID::class.java), rs.getString(2))) }
                        }
                    }
            }
        for (row in rows) {
            val masked = maskFeatureSidecar(raw.get(row.key), fields)
            val sha = sha256Hex(masked)
            val key = keyOf(owner, row.clientActivityId, SynapseKind.FEATURES, sha)
            if (key != row.key) {
                val file = Files.createTempFile("averyn-synapse-mask-", ".bin")
                try {
                    Files.write(file, masked)
                    raw.put(key, file)
                } finally {
                    Files.deleteIfExists(file)
                }
                record(owner, row.clientActivityId, SynapseKind.FEATURES, StoredSidecar(key, sha, masked.size.toLong()))
                raw.delete(row.key)
            }
        }
        return rows.size
    }
}

/** The fields of a feature frame that a consent other than `signal_quality` covers. */
enum class FeatureField(
    /** Offsets of the bytes in a 27-byte frame, and the "not available" byte they take. */
    val offsets: IntRange,
    val na: Int,
) {
    STRESS(16..16, 0xFF),
    RR(10..12, 0xFF), // rr_last (u16, 0xFFFF) and rr_count (u8, 0xFF)
    BAND_HRV(20..23, 0xFF), // rmssd and sdnn, both u16 0xFFFF
}

/** The consent each field of a feature frame needs, besides `signal_quality` which admits the sidecar. */
private val FIELD_SCOPE =
    mapOf(
        FeatureField.STRESS to ConsentScope.STRESS_MONITORING,
        FeatureField.RR to ConsentScope.RR_RECORDING,
        FeatureField.BAND_HRV to ConsentScope.HRV_ANALYSIS,
    )

/** The fields whose scope [isGranted] says no to. Pure given [isGranted]. */
fun ungrantedFields(isGranted: (String) -> Boolean): Set<FeatureField> =
    FeatureField.entries.filterTo(mutableSetOf()) { !isGranted(FIELD_SCOPE.getValue(it)) }

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Sets [fields] to "not available" in every feature frame of a consumer sidecar and leaves everything else as it is,
 * including records that are not feature frames (status records) and a cut tail. Pure.
 */
fun maskFeatureSidecar(
    sidecar: ByteArray,
    fields: Set<FeatureField>,
): ByteArray {
    val out = sidecar.copyOf()
    var at = 0
    while (out.size - at >= 10) {
        val length = (out[at].toInt() and 0xFF) or ((out[at + 1].toInt() and 0xFF) shl 8)
        val from = at + 10
        if (out.size - from < length) break
        if (length >= SYNAPSE_FEATURE_FRAME_BYTES && length != SYNAPSE_STATUS_BYTES && out[from].toInt() == 2) {
            for (f in fields) for (i in f.offsets) out[from + i] = f.na.toByte()
        }
        at = from + length
    }
    return out
}

/**
 * What revoking a consent deletes (ADR-0021, ADR-0030): the whole sidecar when the scope is the one that allowed it,
 * and only the fields of a frame when the scope covers just those. The same hook runs in the application and in the
 * tests, so that what the tests prove is what the server does.
 */
fun revocationHook(
    userData: UserData,
    store: SynapseStore,
): (UUID, String) -> Unit =
    { owner, scope ->
        when (scope) {
            ConsentScope.RR_RECORDING -> {
                userData.deleteRrData(owner)
                store.maskFeatures(owner, setOf(FeatureField.RR))
            }
            ConsentScope.HRV_ANALYSIS -> store.maskFeatures(owner, setOf(FeatureField.BAND_HRV))
            ConsentScope.STRESS_MONITORING -> store.maskFeatures(owner, setOf(FeatureField.STRESS))
            ConsentScope.SIGNAL_QUALITY -> store.deleteKind(owner, SynapseKind.FEATURES)
            ConsentScope.RAW_SIGNALS -> store.deleteKind(owner, SynapseKind.RAW)
            // A scope the server accepts (ConsentScope.ALL) and nothing here deletes for would revoke in name only.
            else -> error("no revocation rule for consent scope '$scope'")
        }
    }
