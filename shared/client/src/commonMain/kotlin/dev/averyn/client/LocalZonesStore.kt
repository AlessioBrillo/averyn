package dev.averyn.client

import dev.averyn.api.PrivacyZones
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The privacy zones as the phone has them, and whether the server has not seen them yet (TDD-0016).
 *
 * Like the profile ([LocalProfileStore]) they live apart from the copy of the server and work with no account:
 * a zone is the user's own setting, and the centre of one is usually a home, so the file is Restricted data.
 */
@Serializable
data class StoredZones(
    val zones: PrivacyZones = PrivacyZones(),
    /** True when the user changed them here and the server does not have the change yet. */
    val unsynced: Boolean = false,
)

/** A small file in [directory]; a missing or unreadable one reads as no zones. */
class LocalZonesStore(
    directory: String,
) {
    private val path = Path(Path(directory), FILE)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): StoredZones {
        if (!SystemFileSystem.exists(path)) return StoredZones()
        return try {
            json.decodeFromString<StoredZones>(SystemFileSystem.source(path).buffered().use { it.readString() })
        } catch (e: Exception) {
            StoredZones()
        }
    }

    /** Written through a temporary file, so a kill half way leaves the previous list. False when it could not be. */
    fun write(stored: StoredZones): Boolean {
        val parent = path.parent ?: return false
        return try {
            if (!SystemFileSystem.exists(parent)) SystemFileSystem.createDirectories(parent)
            val temp = Path(parent, "$FILE.tmp")
            SystemFileSystem.sink(temp, append = false).buffered().use {
                it.writeString(json.encodeToString(StoredZones.serializer(), stored))
            }
            SystemFileSystem.atomicMove(temp, path)
            true
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        const val FILE = "zones.json"
    }
}
