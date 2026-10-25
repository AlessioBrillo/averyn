package dev.averyn.api

/**
 * How the client and the server fit together (TDD-0011, "versions"). The warning is never blocking: the read
 * path is additive, so an app that is a version behind still reads the server.
 */
enum class ClientCompatibility {
    /** Everything fits. */
    OK,

    /** The server speaks an older contract than the app expects: some features may be missing. */
    SERVER_TOO_OLD,

    /** The server speaks a newer contract than the app understands: the app should be updated. */
    APP_TOO_OLD,

    /** The server requires a newer app than the one that is running. */
    APP_BELOW_MINIMUM,

    /** The versions could not be compared (an unparseable version): nothing is claimed. */
    UNKNOWN,
}

/**
 * A semantic version (`major.minor.patch`, with an optional `-pre-release`), compared the way the packaging
 * rules say: the numbers first, a pre-release before the release it precedes.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        val mine = preRelease
        val theirs = other.preRelease
        return when {
            mine == null && theirs == null -> 0
            mine == null -> 1 // a release is greater than any of its pre-releases
            theirs == null -> -1
            else -> mine.compareTo(theirs)
        }
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        /**
         * The version read from a string, or null when it is not one. The build version of an app often carries
         * a suffix (`0.1.0-beta`), which is parsed as the pre-release it names.
         */
        fun parseOrNull(text: String?): SemanticVersion? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val withoutBuild = trimmed.substringBefore('+')
            val main = withoutBuild.substringBefore('-')
            val preRelease = withoutBuild.substringAfter('-', "").ifEmpty { null }
            val parts = main.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val numbers = parts.map { part -> part.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 }) return null
            return SemanticVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
                preRelease = preRelease,
            )
        }
    }
}

/**
 * The versions of the contract, and how the app and the server fit together (TDD-0011).
 *
 * It is an object, not a set of top-level functions, because Swift sees an object as one stable entry point
 * (`Api.shared.checkCompatibility(...)`) while a top-level function lives on a file facade whose name depends
 * on the file it is written in.
 */
object Api {
    /** The version of the HTTP contract this build speaks. */
    const val API_VERSION = 1

    /** What a client of this build understands: the newest contract version it can talk to. */
    const val SUPPORTED_API_VERSION = API_VERSION

    /**
     * What the app should tell the user about the server it is talking to.
     *
     * The comparison never blocks anything: it produces a warning banner, and the read path keeps working.
     */
    fun checkCompatibility(
        serverApiVersion: Int,
        supportedApiVersion: Int = SUPPORTED_API_VERSION,
        minClientVersion: String? = null,
        clientVersion: String? = null,
    ): ClientCompatibility {
        val minimum = SemanticVersion.parseOrNull(minClientVersion)
        val current = SemanticVersion.parseOrNull(clientVersion)
        if (minimum != null && current != null && current < minimum) return ClientCompatibility.APP_BELOW_MINIMUM
        if (serverApiVersion > supportedApiVersion) return ClientCompatibility.APP_TOO_OLD
        if (serverApiVersion < supportedApiVersion) return ClientCompatibility.SERVER_TOO_OLD
        return ClientCompatibility.OK
    }
}
