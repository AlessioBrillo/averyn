package dev.averyn.client

/** Why a call to the server did not produce an answer (TDD-0011). */
enum class ApiFailureKind {
    /** The server refused the token: the user has to sign in again. */
    UNAUTHORIZED,

    /** The server refused the request for good (the account is gone, a consent was revoked). */
    FORBIDDEN,

    /** The activity (or the page) does not exist for this account. */
    NOT_FOUND,

    /** The server is busy computing the analysis: it says when to come back. */
    BUSY,

    /** The network, a 429 or a 5xx: trying again later can work. */
    TRANSIENT,

    /** The answer was not what the contract says. */
    UNEXPECTED,
}

/** What went wrong in a call to the server, and what the app can do about it. */
data class ApiFailure(
    val kind: ApiFailureKind,
    /** The HTTP status, when there was one. */
    val status: Int? = null,
    /** How long the server asked the client to wait, in seconds. */
    val retryAfterSeconds: Long? = null,
    /** The stable code the server sent, when it sent one. */
    val code: String? = null,
)

/**
 * The answer of a call to the server: the value, or the reason there is none.
 *
 * A data class rather than an exception: the apps show the reason (offline, sign in again, being deleted) and
 * never let a network problem become a crash.
 */
data class ApiResult<T>(
    val value: T? = null,
    val failure: ApiFailure? = null,
) {
    val isSuccess: Boolean get() = failure == null && value != null
}
