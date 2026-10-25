package dev.averyn.backend.activities

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivitySummary
import dev.averyn.api.UserProfile
import dev.averyn.backend.consent.ConsentGrant
import dev.averyn.backend.consent.ConsentStore
import dev.averyn.backend.profile.ProfileStore
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.exportGpxToFile
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.sql.DataSource

/** One activity in `activities.json`: its summary and how many unreadable raw lines the GPX had to skip (F5). */
@Serializable
data class ExportedActivity(
    val activity: ActivitySummary,
    val gpxSkippedLines: Int,
)

@Serializable
data class ExportManifest(
    val exportedAt: String,
    val activities: List<ExportedActivity>,
)

/**
 * Deleting and exporting what a user has stored (ADR-0017, TDD-0004). Blocking (JDBC, S3): call from
 * `Dispatchers.IO`. Every step is safe to repeat, so a failure half-way is finished by calling it again.
 */
class UserData(
    private val db: DataSource,
    private val raw: RawObjectStore,
    private val consentStore: ConsentStore = ConsentStore(db),
    private val analysisService: AnalysisService = AnalysisService(db, raw),
    private val recordStore: PersonalRecordStore = PersonalRecordStore(db),
    private val profileStore: ProfileStore = ProfileStore(db),
) {
    /**
     * Removes one activity and remembers its id so that a device retry gets `410`. Returns false when the caller
     * has no such activity (also when it is someone else's). The object goes first: a failure leaves a row without
     * its file (a retry completes it), never a file without a row.
     *
     * Everything the activity owns is deleted, not only its canonical file: the imported original, the
     * heart-rate sidecar. The whole per-activity prefix goes, so a file the code forgot to record is deleted
     * too.
     */
    fun deleteActivity(
        ownerId: UUID,
        id: UUID,
    ): Boolean {
        val found =
            db.connection.use { conn ->
                val sql = "SELECT client_activity_id FROM activities WHERE id = ? AND owner_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, ownerId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1, UUID::class.java) else null
                    }
                }
            } ?: return false
        raw.deletePrefix("raw/$ownerId/$found/")
        inTransaction { conn ->
            conn.prepareStatement("DELETE FROM activities WHERE id = ? AND owner_id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, ownerId)
                stmt.executeUpdate()
            }
            val tombstone =
                "INSERT INTO deleted_activities (owner_id, client_activity_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
            conn.prepareStatement(tombstone).use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setObject(2, found)
                stmt.executeUpdate()
            }
            // The sidecar of a deleted activity is gone with it, and it must not come back with a later upload.
            val rrTombstone =
                "INSERT INTO deleted_rr (owner_id, client_activity_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
            conn.prepareStatement(rrTombstone).use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setObject(2, found)
                stmt.executeUpdate()
            }
        }
        return true
    }

    /**
     * Deletes everything Averyn holds for the user and flags the account. The flag comes first, so the account
     * stops working at once; the rest can be repeated until it succeeds. The identity at the IdP is not touched.
     */
    fun deleteAccount(ownerId: UUID) {
        inTransaction { conn ->
            val flag = "UPDATE users SET deleted_at = COALESCE(deleted_at, now()) WHERE id = ?"
            conn.prepareStatement(flag).use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.executeUpdate()
            }
        }
        raw.deletePrefix("raw/$ownerId/") // also removes objects orphaned by failed uploads
        inTransaction { conn ->
            for (table in listOf("activities", "deleted_activities", "deleted_rr", "consents", "user_profiles")) {
                conn.prepareStatement("DELETE FROM $table WHERE owner_id = ?").use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Deletes the heart-rate sidecars of the user and remembers that they are gone, so a device that still has
     * them cannot upload them again (ADR-0021: revoking the consent really deletes the data it covers).
     */
    fun deleteRrData(ownerId: UUID): Int {
        val sidecars =
            db.connection.use { conn ->
                conn
                    .prepareStatement(
                        "SELECT client_activity_id, rr_object_key FROM activities WHERE owner_id = ? AND rr_object_key IS NOT NULL",
                    ).use { stmt ->
                        stmt.setObject(1, ownerId)
                        stmt.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) add(rs.getObject(1, UUID::class.java) to rs.getString(2))
                            }
                        }
                    }
            }
        sidecars.forEach { (_, key) -> raw.delete(key) }
        inTransaction { conn ->
            conn
                .prepareStatement(
                    "UPDATE activities SET rr_object_key = NULL, rr_sha256 = NULL, rr_size_bytes = NULL WHERE owner_id = ?",
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeUpdate()
                }
            val tombstone =
                "INSERT INTO deleted_rr (owner_id, client_activity_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
            conn.prepareStatement(tombstone).use { stmt ->
                sidecars.forEach { (clientActivityId, _) ->
                    stmt.setObject(1, ownerId)
                    stmt.setObject(2, clientActivityId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        return sidecars.size
    }

    /**
     * The object keys of [ownerId]'s activities whose raw file is not in the store. An export of such an account
     * cannot be complete, and starting the ZIP would answer `200` before finding out: the route checks this first.
     */
    fun missingRawObjects(ownerId: UUID): List<String> =
        db.connection
            .use { conn ->
                conn
                    .prepareStatement(
                        "SELECT raw_object_key, source_object_key, rr_object_key FROM activities WHERE owner_id = ?",
                    ).use { stmt ->
                        stmt.setObject(1, ownerId)
                        stmt.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(rs.getString(1))
                                    rs.getString(2)?.let { add(it) }
                                    rs.getString(3)?.let { add(it) }
                                }
                            }
                        }
                    }
            }.filterNot { raw.exists(it) }

    /**
     * Writes a ZIP of everything the user has to [out]: each raw file unchanged, the file it was imported from
     * (when there is one), the heart-rate sidecar (when there is one), a GPX of it, the consent grants and
     * `activities.json` (last, because it records what the GPX skipped). One file is in memory at a time.
     */
    fun export(
        ownerId: UUID,
        out: OutputStream,
    ) {
        val rows =
            db.connection.use { conn ->
                val sql =
                    "SELECT raw_object_key, source_object_key, rr_object_key, $SUMMARY_COLUMNS " +
                        "FROM activities WHERE owner_id = ? ORDER BY started_at, id"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Triple(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                    ) to rs.toSummary(columnOffset = 3),
                                )
                            }
                        }
                    }
                }
            }
        val workDir = Files.createTempDirectory("averyn-export-")
        try {
            val store = ActivityStore(Path(workDir.toString()))
            val exported = ArrayList<ExportedActivity>()
            ZipOutputStream(out).use { zip ->
                for ((keys, summary) in rows) {
                    val id = summary.clientActivityId
                    val bytes = raw.get(keys.first)
                    zip.putNextEntry(ZipEntry("raw/$id.jsonl"))
                    zip.write(bytes)
                    zip.closeEntry()

                    // The file the activity was imported from, byte-for-byte: what the user gave us.
                    keys.second?.let { sourceKey ->
                        zip.putNextEntry(ZipEntry("original/$id.gpx"))
                        zip.write(raw.get(sourceKey))
                        zip.closeEntry()
                    }

                    // The heart-rate sidecar, when the activity has one.
                    keys.third?.let { rrKey ->
                        zip.putNextEntry(ZipEntry("rr/$id.rr.jsonl"))
                        zip.write(raw.get(rrKey))
                        zip.closeEntry()
                    }

                    val rawFile = workDir.resolve("$id.jsonl")
                    val gpx = workDir.resolve("$id.gpx")
                    Files.write(rawFile, bytes)
                    val skipped = store.exportGpxToFile(id, gpx.toString())
                    zip.putNextEntry(ZipEntry("gpx/$id.gpx"))
                    Files.copy(gpx, zip)
                    zip.closeEntry()
                    Files.delete(gpx)
                    Files.delete(rawFile)

                    // The analysis of the activity: the splits, the elevation and the series, computed when it
                    // is missing. It is derived data, so a failure here does not hold back the raw file.
                    analysisService
                        .analysisOrNull(ownerId, UUID.fromString(summary.id))
                        ?.let { analysis ->
                            zip.putNextEntry(ZipEntry("analysis/$id.json"))
                            zip.write(Json.encodeToString(ActivityAnalysis.serializer(), analysis).toByteArray())
                            zip.closeEntry()
                        }

                    exported += ExportedActivity(summary, skipped)
                }
                // The settings of the account (TDD-0012): units and maximum heart rate.
                zip.putNextEntry(ZipEntry("profile.json"))
                zip.write(Json.encodeToString(UserProfile.serializer(), profileStore.get(ownerId)).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("consents.json"))
                zip.write(
                    Json.encodeToString(consentStore.list(ownerId).map { it.toExported() }).toByteArray(),
                )
                zip.closeEntry()
                // The personal records of the account: derived data like the analyses, and as useful as they.
                // They are written after the activities, so that the best efforts of the activities that were
                // just analysed are part of them.
                zip.putNextEntry(ZipEntry("records.json"))
                zip.write(Json.encodeToString(PersonalRecords.serializer(), recordStore.records(ownerId)).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("activities.json"))
                zip.write(Json.encodeToString(ExportManifest(Instant.now().toString(), exported)).toByteArray())
                zip.closeEntry()
            }
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private fun ConsentGrant.toExported() = ExportedConsentGrant(scope, grantedAt.toString(), revokedAt?.toString())

    private fun inTransaction(block: (Connection) -> Unit) {
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

/** One consent grant in `consents.json`: the audit record of what the user allowed (ADR-0021). */
@Serializable
data class ExportedConsentGrant(
    val scope: String,
    val grantedAt: String,
    val revokedAt: String?,
)
