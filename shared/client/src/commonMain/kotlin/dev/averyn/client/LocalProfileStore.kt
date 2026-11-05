package dev.averyn.client

import dev.averyn.api.UserProfile
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The profile as the phone has it, and whether the server has not seen it yet.
 *
 * It lives apart from the copy of the server ([ActivityCache]): that copy belongs to a session and goes with it,
 * while the units and the maximum heart rate are the user's own settings and must work with no account (ADR-0028).
 */
@Serializable
data class StoredProfile(
    val profile: UserProfile = UserProfile(),
    /** True when the user changed it here and the server does not have the change yet. */
    val unsynced: Boolean = false,
)

/** A small file in [directory]; a missing or unreadable one reads as the default profile (metric, no maximum). */
class LocalProfileStore(
    directory: String,
) {
    private val path = Path(Path(directory), FILE)
    private val json = Json { ignoreUnknownKeys = true }

    fun exists(): Boolean = SystemFileSystem.exists(path)

    fun read(): StoredProfile {
        if (!SystemFileSystem.exists(path)) return StoredProfile()
        return try {
            json.decodeFromString<StoredProfile>(SystemFileSystem.source(path).buffered().use { it.readString() })
        } catch (e: Exception) {
            StoredProfile()
        }
    }

    /**
     * Written through a temporary file, so a kill half way leaves the previous profile. False when it could not be
     * written (disk full): like the other files next to an activity it is a result to report, not a crash.
     */
    fun write(stored: StoredProfile): Boolean {
        val parent = path.parent ?: return false
        return try {
            if (!SystemFileSystem.exists(parent)) SystemFileSystem.createDirectories(parent)
            val temp = Path(parent, "$FILE.tmp")
            SystemFileSystem.sink(temp, append = false).buffered().use {
                it.writeString(json.encodeToString(StoredProfile.serializer(), stored))
            }
            SystemFileSystem.atomicMove(temp, path)
            true
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        const val FILE = "profile.json"
    }
}
