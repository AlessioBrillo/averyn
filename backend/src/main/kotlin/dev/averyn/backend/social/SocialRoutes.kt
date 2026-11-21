package dev.averyn.backend.social

import dev.averyn.api.RelationResult
import dev.averyn.api.ReportRequest
import dev.averyn.api.SocialProfile
import dev.averyn.api.Visibilities
import dev.averyn.api.VisibilityRequest
import dev.averyn.api.VisibilityResult
import dev.averyn.backend.activities.ActivityCursor
import dev.averyn.backend.activities.ActivityIngest
import dev.averyn.backend.activities.DEFAULT_PAGE_SIZE
import dev.averyn.backend.activities.DEFAULT_USER_QUOTA_BYTES
import dev.averyn.backend.activities.MAX_PAGE_SIZE
import dev.averyn.backend.activities.PER_IP
import dev.averyn.backend.activities.PER_USER
import dev.averyn.backend.activities.errorBody
import dev.averyn.backend.activities.owner
import dev.averyn.backend.activities.perUserRateLimit
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.privacy.PrivacyZoneStore
import dev.averyn.backend.storage.RawObjectStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

private val GEO_JSON = ContentType("application", "geo+json")
private val json = Json { ignoreUnknownKeys = true }
private val random = SecureRandom()
private val TOKEN = Regex("[A-Za-z0-9_-]{20,64}")

/** Largest JSON body of a social call: every one of them is a handful of short fields. */
private const val MAX_JSON_BYTES = 4 * 1024

/** Every response on a path that shows someone else's data carries this: no search engine may keep it (F11). */
private val noIndex =
    createApplicationPlugin("NoIndexForOthers") {
        onCall { call ->
            val path = call.request.path()
            if (path.startsWith("/v1/users/") || path.startsWith("/v1/shared/")) {
                call.response.header("X-Robots-Tag", "noindex, nofollow")
            }
        }
    }

/**
 * The social foundation (TDD-0018, ADR-0036): the public profile, follows, blocks, reports, the visibility of an
 * activity and the pages of other people. Everything that returns an activity of someone else goes through
 * [ActivityAccess]; `/v1/shared/{token}` is the only unauthenticated route and is rate limited per address.
 *
 * ponytail: lists are not paged (200 people at most); the in-app badge is the count of waiting requests.
 */
fun Application.social(
    db: DataSource,
    raw: RawObjectStore,
    quotaBytes: Long = DEFAULT_USER_QUOTA_BYTES,
    zoneStore: PrivacyZoneStore = PrivacyZoneStore(db),
    store: SocialStore = SocialStore(db),
    access: ActivityAccess = ActivityAccess(db, ActivityIngest(db, raw, quotaBytes), zoneStore),
) {
    perUserRateLimit()
    if (pluginOrNull(noIndex) == null) install(noIndex)

    routing {
        rateLimit(PER_IP) {
            get("/v1/shared/{token}") {
                val token = call.parameters["token"]?.takeIf { TOKEN.matches(it) }
                val activity = token?.let { io { access.shared(it) } }
                if (activity ==
                    null
                ) {
                    call.respond(HttpStatusCode.NotFound, errorBody("not found"))
                } else {
                    call.respond(activity)
                }
            }
            get("/v1/shared/{token}/track") {
                val token = call.parameters["token"]?.takeIf { TOKEN.matches(it) }
                val feature = token?.let { io { access.sharedTrack(it) } }
                if (feature == null) call.respondNotFound() else call.respondText(feature, GEO_JSON)
            }
        }

        authenticate(OIDC_AUTH) {
            rateLimit(PER_USER) {
                // ---- the owner's own public profile ----
                get("/v1/me/public-profile") {
                    val me = call.owner(db) ?: return@get
                    call.respond(io { store.profile(me) })
                }

                put("/v1/me/public-profile") {
                    val me = call.owner(db) ?: return@put
                    val body = call.jsonBody(SocialProfile.serializer()) ?: return@put
                    val p =
                        body.copy(
                            handle = body.handle?.trim()?.lowercase(),
                            displayName = body.displayName?.trim()?.takeIf { it.isNotEmpty() },
                            bio = body.bio?.trim()?.takeIf { it.isNotEmpty() },
                            sport = body.sport?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    val problem = p.problem()
                    if (problem != null) return@put call.respond(HttpStatusCode.UnprocessableEntity, errorBody(problem))
                    when (val result = io { store.putProfile(me, p) }) {
                        is ProfileResult.Saved -> call.respond(result.profile)
                        ProfileResult.HandleTaken ->
                            call.respond(HttpStatusCode.Conflict, errorBody("that handle is taken"))
                        is ProfileResult.HandleTooSoon ->
                            call.respond(
                                HttpStatusCode.Conflict,
                                errorBody(
                                    "a handle can change once every ${SocialProfile.HANDLE_CHANGE_DAYS} days, next on ${result.until}",
                                ),
                            )
                    }
                }

                put("/v1/me/avatar") {
                    val me = call.owner(db) ?: return@put
                    val bytes = call.receiveChannel().readRemaining(MAX_AVATAR_BYTES + 1L).readByteArray()
                    if (bytes.size > MAX_AVATAR_BYTES) {
                        return@put call.respond(HttpStatusCode.PayloadTooLarge, errorBody("picture too large"))
                    }
                    val jpeg =
                        io { reencodeAvatar(bytes) }
                            ?: return@put call.respond(
                                HttpStatusCode.UnprocessableEntity,
                                errorBody("not a picture the server can read"),
                            )
                    val key = "avatars/$me/${sha256(jpeg)}.jpg"
                    io {
                        val file = Files.createTempFile("averyn-avatar-", ".jpg")
                        try {
                            Files.write(file, jpeg)
                            raw.put(key, file, "image/jpeg")
                        } finally {
                            Files.deleteIfExists(file)
                        }
                        store.setAvatarKey(me, key)?.takeIf { it != key }?.let { raw.delete(it) }
                    }
                    call.respond(io { store.profile(me) })
                }

                delete("/v1/me/avatar") {
                    val me = call.owner(db) ?: return@delete
                    io { store.setAvatarKey(me, null)?.let { raw.delete(it) } }
                    call.respond(HttpStatusCode.NoContent)
                }

                // ---- the visibility of the owner's own activity ----
                put("/v1/activities/{id}/visibility") {
                    val me = call.owner(db) ?: return@put
                    val id = call.idParam() ?: return@put
                    val request = call.jsonBody(VisibilityRequest.serializer()) ?: return@put
                    if (request.visibility !in Visibilities.ALL) {
                        return@put call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            errorBody("visibility must be one of ${Visibilities.ALL.joinToString()}"),
                        )
                    }
                    val (found, token) = io { store.setVisibility(me, id, request.visibility, newToken()) }
                    if (!found) return@put call.respondNotFound()
                    call.respond(VisibilityResult(request.visibility, token))
                }

                // ---- other people ----
                get("/v1/users/by-handle/{handle}") {
                    val me = call.owner(db) ?: return@get
                    val id =
                        call.parameters["handle"]?.lowercase()?.let { io { store.idByHandle(it) } }
                            ?: return@get call.respondNotFound()
                    call.respondCard(me, id, access, store)
                }

                get("/v1/users/{id}") {
                    val me = call.owner(db) ?: return@get
                    call.respondCard(me, call.idParam() ?: return@get, access, store)
                }

                get("/v1/users/{id}/avatar") {
                    val me = call.owner(db) ?: return@get
                    val id = call.idParam() ?: return@get
                    val relation = io { access.relation(me, id) }?.takeUnless { !it.self && it.blocked }
                    val key = relation?.let { io { store.avatarKey(id) } } ?: return@get call.respondNotFound()
                    call.response.header(HttpHeaders.CacheControl, "private, max-age=300")
                    call.respondBytes(io { raw.get(key) }, ContentType.Image.JPEG)
                }

                get("/v1/users/{id}/activities") {
                    val me = call.owner(db) ?: return@get
                    val id = call.idParam() ?: return@get
                    val rawLimit = call.request.queryParameters["limit"]
                    val limit =
                        if (rawLimit == null) {
                            DEFAULT_PAGE_SIZE
                        } else {
                            rawLimit.toIntOrNull()?.takeIf { it in 1..MAX_PAGE_SIZE }
                                ?: return@get call.respond(
                                    HttpStatusCode.BadRequest,
                                    errorBody("limit must be 1..$MAX_PAGE_SIZE"),
                                )
                        }
                    val after =
                        call.request.queryParameters["cursor"]?.let {
                            ActivityCursor.parseOrNull(it)
                                ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed cursor"))
                        }
                    val page = io { access.page(me, id, limit, after) } ?: return@get call.respondNotFound()
                    call.respond(page)
                }

                get("/v1/users/{id}/activities/{aid}") {
                    val me = call.owner(db) ?: return@get
                    val id = call.idParam() ?: return@get
                    val aid = call.idParam("aid") ?: return@get
                    call.respond(io { access.activity(me, id, aid) } ?: return@get call.respondNotFound())
                }

                get("/v1/users/{id}/activities/{aid}/track") {
                    val me = call.owner(db) ?: return@get
                    val id = call.idParam() ?: return@get
                    val aid = call.idParam("aid") ?: return@get
                    val feature = io { access.track(me, id, aid) } ?: return@get call.respondNotFound()
                    call.respondText(feature, GEO_JSON)
                }

                // ---- follow ----
                post("/v1/users/{id}/follow") {
                    val me = call.owner(db) ?: return@post
                    val id = call.idParam() ?: return@post
                    if (id ==
                        me
                    ) {
                        return@post call.respond(HttpStatusCode.BadRequest, errorBody("you cannot follow yourself"))
                    }
                    val relation =
                        io { access.relation(me, id) }?.takeUnless { it.blocked } ?: return@post call.respondNotFound()
                    if (relation.followState == null && io { store.tooManyPending(me) }) {
                        return@post call.respond(HttpStatusCode.TooManyRequests, errorBody("too many requests waiting"))
                    }
                    io { store.follow(me, id) }
                    call.respond(
                        io { access.relation(me, id) }?.let { RelationResult(relationName(it)) }
                            ?: return@post call.respondNotFound(),
                    )
                }

                delete("/v1/users/{id}/follow") {
                    val me = call.owner(db) ?: return@delete
                    val id = call.idParam() ?: return@delete
                    io { store.unfollow(me, id) }
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/v1/me/follow-requests") {
                    val me = call.owner(db) ?: return@get
                    call.respond(io { store.requests(me) })
                }

                post("/v1/me/follow-requests/{id}/accept") {
                    val me = call.owner(db) ?: return@post
                    val id = call.idParam() ?: return@post
                    // A block that came after the request wins: it was already removed with the follow.
                    if (io { store.accept(me, id) }) call.respond(HttpStatusCode.NoContent) else call.respondNotFound()
                }

                post("/v1/me/follow-requests/{id}/decline") {
                    val me = call.owner(db) ?: return@post
                    val id = call.idParam() ?: return@post
                    if (io { store.decline(me, id) }) call.respond(HttpStatusCode.NoContent) else call.respondNotFound()
                }

                delete("/v1/me/followers/{id}") {
                    val me = call.owner(db) ?: return@delete
                    val id = call.idParam() ?: return@delete
                    io { store.removeFollower(me, id) }
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/v1/users/{id}/followers") {
                    val me = call.owner(db) ?: return@get
                    val id = call.idParam() ?: return@get
                    val relation =
                        io { access.relation(me, id) }?.takeUnless { !it.self && it.blocked }
                            ?: return@get call.respondNotFound()
                    val hidden = !relation.self && !relation.following && relation.profilePrivate
                    call.respond(if (hidden) emptyList() else io { store.followers(me, id) })
                }

                get("/v1/users/{id}/following") {
                    val me = call.owner(db) ?: return@get
                    val id = call.idParam() ?: return@get
                    val relation =
                        io { access.relation(me, id) }?.takeUnless { !it.self && it.blocked }
                            ?: return@get call.respondNotFound()
                    val hidden = !relation.self && !relation.following && relation.profilePrivate
                    call.respond(if (hidden) emptyList() else io { store.following(me, id) })
                }

                // ---- block ----
                get("/v1/me/blocks") {
                    val me = call.owner(db) ?: return@get
                    call.respond(io { store.blocks(me) })
                }

                put("/v1/me/blocks/{id}") {
                    val me = call.owner(db) ?: return@put
                    val id = call.idParam() ?: return@put
                    if (id ==
                        me
                    ) {
                        return@put call.respond(HttpStatusCode.BadRequest, errorBody("you cannot block yourself"))
                    }
                    if (io { access.relation(me, id) } == null) return@put call.respondNotFound()
                    io { store.block(me, id) }
                    call.respond(HttpStatusCode.NoContent)
                }

                delete("/v1/me/blocks/{id}") {
                    val me = call.owner(db) ?: return@delete
                    val id = call.idParam() ?: return@delete
                    io { store.unblock(me, id) }
                    call.respond(HttpStatusCode.NoContent)
                }

                // ---- report ----
                post("/v1/reports") {
                    val me = call.owner(db) ?: return@post
                    val request = call.jsonBody(ReportRequest.serializer()) ?: return@post
                    request.problem()?.let {
                        return@post call.respond(HttpStatusCode.UnprocessableEntity, errorBody(it))
                    }
                    val target = request.targetUserId.toUuidOrNull()
                    val activity =
                        request.targetActivityId?.let {
                            it.toUuidOrNull()
                                ?: return@post call.respondNotFound()
                        }
                    if (target == null || target == me) {
                        return@post call.respond(HttpStatusCode.UnprocessableEntity, errorBody("not a user to report"))
                    }
                    // A reporter can only report what they can see: the same door as everyone else.
                    val visible =
                        io {
                            val relation = access.relation(me, target)
                            relation != null &&
                                !relation.blocked &&
                                (activity == null || access.activity(me, target, activity) != null)
                        }
                    if (!visible) return@post call.respondNotFound()
                    if (io { store.tooManyReports(me) }) {
                        return@post call.respond(HttpStatusCode.TooManyRequests, errorBody("too many open reports"))
                    }
                    io { store.report(me, request, target, activity) }
                    call.respond(HttpStatusCode.Created)
                }
            }
        }
    }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private suspend fun ApplicationCall.respondNotFound() = respond(HttpStatusCode.NotFound, errorBody("not found"))

private suspend fun ApplicationCall.respondCard(
    me: UUID,
    id: UUID,
    access: ActivityAccess,
    store: SocialStore,
) {
    // Someone who blocked the viewer does not exist for the viewer.
    val relation = io { access.relation(me, id) }?.takeUnless { it.blockedViewer } ?: return respondNotFound()
    respond(io { store.card(id, relation) })
}

private suspend fun ApplicationCall.idParam(name: String = "id"): UUID? =
    parameters[name]?.toUuidOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, errorBody("malformed id"))
        null
    }

/** A JSON body of at most [MAX_JSON_BYTES]; a body that is not one answers `422` and returns null. */
private suspend fun <T> ApplicationCall.jsonBody(serializer: KSerializer<T>): T? {
    val bytes = receiveChannel().readRemaining(MAX_JSON_BYTES + 1L).readByteArray()
    val parsed =
        if (bytes.size > MAX_JSON_BYTES) {
            null
        } else {
            try {
                json.decodeFromString(serializer, bytes.decodeToString())
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    if (parsed == null) respond(HttpStatusCode.UnprocessableEntity, errorBody("not a valid body"))
    return parsed
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun newToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { random.nextBytes(it) })

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
