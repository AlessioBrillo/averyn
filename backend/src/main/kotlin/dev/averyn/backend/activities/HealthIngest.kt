package dev.averyn.backend.activities

import dev.averyn.backend.storage.RawObjectStore
import java.nio.ByteBuffer
import java.nio.file.Files
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A single record parsed out of a Synapse FIT file's developer data block
 * (message 209). Flat scalars only, per the canonical table in
 * docs/integrations/synapse-import.md.
 *
 * `kind` is one of `hrv`, `stress`, `sleep`, `sqi`, `temperature`.
 */
data class SynapseFitRecord(
    val recordedAt: Instant,
    val kind: String,
    val valueJson: String,
)

/**
 * Parses a FIT file and returns the Synapse developer-data records it
 * contains. Only the flat scalar developer layout written by
 * `scripts/export_session_fit.py` (SYNAPSE-24 repo) is understood:
 * message 209 (developer data) with timestamp + developer_data_index +
 * fields in fixed order.
 *
 * Returns an empty list for files without Synapse developer data rather than
 * throwing, so the caller can decide (store what we can, skip the rest).
 */
fun parseFitDeveloperData(bytes: ByteArray): List<SynapseFitRecord> {
    if (bytes.size < 14 || String(bytes.copyOfRange(8, 12), Charsets.UTF_8) != ".FIT") return emptyList()

    val records = mutableListOf<SynapseFitRecord>()
    var pos = 14 // skip 14-byte header (header CRC already included)
    val end = bytes.size

    // local msg type -> global mesg num (from definition messages)
    val defs = mutableMapOf<Int, Int>()
    // local msg types used for developer data (global mesg num 209)
    val devLocalTypes = mutableSetOf<Int>()
    // local msg type -> data payload bytes per record (from field defs)
    val defDataLen = mutableMapOf<Int, Int>()

    fun fieldBytes(baseType: Int): Int = when (baseType) {
        0, 2, 131 -> 1   // enum, uint8, sint8
        132 -> 2          // uint16
        133, 134, 136 -> 4 // sint32, uint32, float32
        137 -> 8          // float64
        else -> 1
    }

    while (pos < end) {
        val b = bytes[pos].toInt() and 0xFF
        pos++
        val isDef = b and 0x80 != 0
        val lt = b and 0x3F
        if (isDef) {
            if (pos + 5 > end) break
            pos += 2 // reserved + arch
            val gnum = bytes[pos].toInt() and 0xFF or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            val nFields = bytes[pos].toInt() and 0xFF
            pos++
            defs[lt] = gnum
            if (gnum == 209) devLocalTypes.add(lt)
            var total = 0
            for (_ in 0 until nFields) {
                if (pos + 3 > end) break
                // field definition number (1), size (1), base type (1)
                val fSize = bytes[pos + 1].toInt() and 0xFF
                pos += 3
                total += fSize // size is declared explicitly in the definition
            }
            defDataLen[lt] = total
            if (pos + 2 <= end) pos += 2 // definition CRC
            continue
        }

        // Data message (header byte already consumed).
        if (lt in devLocalTypes) {
            // developer data: timestamp(u32) + developer_data_index(u8) + values
            if (pos + 5 > end) break
            val ts =
                bytes[pos].toInt() and 0xFF or
                    ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            pos++ // developer_data_index (we use index 0)

            val fields = mutableMapOf<Int, Int>()
            for (fieldId in listOf(0, 1, 2, 3, 4, 5, 6, 7)) {
                if (pos + 1 > end) break
                var v = bytes[pos].toInt() and 0xFF
                pos++
                if (fieldId in listOf(5, 6, 7)) { // uint16 fields: rmssd, sdnn, lf_hf
                    if (pos + 1 > end) break
                    v = v or ((bytes[pos].toInt() and 0xFF) shl 8)
                    pos++
                }
                fields[fieldId] = v
            }
            val recordedAt = Instant.ofEpochSecond(ts - 315964800) // FIT epoch 1989-12-31 -> unix
            records.add(
                SynapseFitRecord(
                    recordedAt = recordedAt,
                    kind = synapseKind(fields),
                    valueJson = fitValueJson(fields),
                )
            )
            if (pos + 2 <= end) pos += 2 // developer data CRC
        } else if (lt in defDataLen) {
            // standard record: skip payload + CRC
            val skip = defDataLen[lt]!!
            if (pos + skip + 2 > end) break
            pos += skip + 2
        } else {
            break // unknown layout: stop rather than corrupt
        }
    }

    return records
}

/** Maps the raw developer field values to a single "kind" for storage. */
private fun synapseKind(fields: Map<Int, Int>): String {
    // The FIT record carries multiple signals at once; we store one row per
    // signal so Averyn can query by kind. The primary kind is hrv when RMSSD
    // / SDNN are present, otherwise stress, otherwise sqi.
    return when {
        fields[5] != null && fields[5] != 0xFFFF -> "hrv"    // rmssd
        fields[0] != null && fields[0] != 0xFF -> "stress"   // stress_level
        fields[1] != null && fields[1] != 0xFF -> "sqi"      // ppg_sqi
        else -> "hrv"
    }
}

/** Serialises the raw developer fields into the JSON stored in value_json. */
private fun fitValueJson(fields: Map<Int, Int>): String {
    val b = StringBuilder("{")
    val entries = fields.entries.sortedBy { it.key }.filter { it.value != null && it.value !in (0xFF, 0xFFFF) }
    b.joinToString(entries, separator = ", ", prefix = "", postfix = "") { (k, v) ->
        "\"$k\":$v"
    }
    b.append("}")
    return b.toString()
}

/**
 * Ingests a FIT file uploaded by the user. Creates/binds a `devices` row by
 * `vendorSerial`, stores each developer-data record in
 * `imported_health_samples` with first-class provenance, and associates them
 * with `activityId` when provided.
 *
 * Caller must already have verified consent for the relevant scopes
 * (hrv_analysis, stress_monitoring, sleep_staging, signal_quality) before
 * invoking this for health kinds.
 */
fun ingestFit(
    db: DataSource,
    ownerId: UUID,
    file: java.nio.file.Path,
    deviceSerial: String?,
    activityId: UUID?,
): HealthIngestResult {
    val bytes = Files.readAllBytes(file)
    val records = parseFitDeveloperData(bytes)

    // Bind or create the device (serial from BLE 0x2A25, day-1 binding key).
    val deviceId =
        db.connection.use { conn ->
            deviceSerial?.takeIf { it.isNotEmpty() }?.let { serial ->
                val find =
                    "SELECT id FROM devices WHERE owner_id = ? AND vendor_serial = ?"
                conn.prepareStatement(find).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setString(2, serial)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1, UUID::class.java) else null
                    }
                }
            } ?: run {
                val insert =
                    "INSERT INTO devices (owner_id, kind, vendor_serial) VALUES (?, 'synapse-band', ?)"
                conn.prepareStatement(insert, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setString(2, "SYN-UNKNOWN")
                    stmt.executeUpdate()
                    stmt.generatedKeys.use { rs ->
                        if (rs.next()) rs.getObject(1, UUID::class.java) else null
                    }
                }
            }
        } ?: return HealthIngestResult.Failure("could not bind device")

    // Store each record, owner-scoped, with provenance.
    val algorithmVersion = "export_session_fit-v1"
    val source = "fit-file"
    var inserted = 0
    db.connection.use { conn ->
        conn.autoCommit = false
        try {
            val sql =
                """INSERT INTO imported_health_samples
                   (owner_id, activity_id, device_id, kind, recorded_at, value_json, source, algorithm_version)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
            conn.prepareStatement(sql).use { stmt ->
                for (r in records) {
                    stmt.setObject(1, ownerId)
                    stmt.setObject(2, activityId)
                    stmt.setObject(3, deviceId)
                    stmt.setString(4, r.kind)
                    stmt.setTimestamp(5, Timestamp.from(r.recordedAt))
                    stmt.setString(6, r.valueJson)
                    stmt.setString(7, source)
                    stmt.setString(8, algorithmVersion)
                    stmt.executeUpdate()
                    inserted++
                }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    return HealthIngestResult.Success(inserted, deviceId, algorithmVersion, source)
}

sealed class HealthIngestResult {
    data class Success(
        val imported: Int,
        val deviceId: UUID,
        val algorithmVersion: String,
        val source: String,
    ) : HealthIngestResult()
    data class Failure(val reason: String) : HealthIngestResult()
}
