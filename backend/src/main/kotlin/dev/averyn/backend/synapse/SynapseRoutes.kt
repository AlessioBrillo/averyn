package dev.averyn.backend.synapse

import dev.averyn.api.SynapseSummaryDto
import dev.averyn.backend.activities.DEFAULT_REQUESTS_PER_MINUTE
import dev.averyn.backend.activities.PER_USER
import dev.averyn.backend.activities.errorBody
import dev.averyn.backend.activities.owner
import dev.averyn.backend.activities.perUserRateLimit
import dev.averyn.backend.activities.receiveToFile
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.consent.ConsentScope
import dev.averyn.backend.consent.ConsentStore
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.sensors.SynapseRawStream
import dev.averyn.sensors.readSynapseSidecar
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentLength
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource

/** The longest sidecar of each kind: about four hours of the raw streams (2.4 kB/s) and a day of features. */
const val MAX_RAW_SIDECAR_BYTES = 48L * 1024 * 1024
const val MAX_FEATURE_SIDECAR_BYTES = 4L * 1024 * 1024

/** A window request never returns more buckets than this per segment, whatever the client asks. */
const val MAX_WINDOW_POINTS = 4_000
private const val DEFAULT_WINDOW_POINTS = 1_000

/** Recordings kept decoded, by checksum: a user zooming and panning asks for many windows of the same file. */
private const val RECORDING_CACHE_SIZE = 4

/**
 * The Synapse sidecars of an activity (ADR-0030): `PUT /v1/activities/{id}/synapse/{features|raw}`, then
 * `GET /v1/activities/{id}/synapse` for what was recorded and `GET .../synapse/raw/{stream}` for a window of a raw
 * signal. Owner-only, and each kind needs its consent both to be stored and to be read (fail closed).
 *
 * ponytail: the min/max pyramid of a recording is built when it is first read and kept in a small in-memory cache, not
 * at upload and not persisted. Fine for a self-hosted instance; persist it next to the sidecar when reads get slow.
 */
fun Application.synapse(
    db: DataSource,
    raw: RawObjectStore,
    consentStore: ConsentStore,
    quotaBytes: Long,
    requestsPerMinute: Int = DEFAULT_REQUESTS_PER_MINUTE,
    store: SynapseStore = SynapseStore(db, raw),
) {
    perUserRateLimit(requestsPerMinute)
    val cache =
        object : LinkedHashMap<String, SynapseRecording>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SynapseRecording>) =
                size > RECORDING_CACHE_SIZE
        }

    fun recordingOf(stored: StoredSidecar): SynapseRecording =
        synchronized(cache) { cache[stored.sha256] }
            ?: SynapseRecording
                .read(
                    raw.get(stored.objectKey),
                ).also { synchronized(cache) { cache[stored.sha256] = it } }

    routing {
        authenticate(OIDC_AUTH) {
            rateLimit(PER_USER) {
                put("/v1/activities/{clientActivityId}/synapse/{kind}") {
                    val clientId =
                        call.uuid("clientActivityId")
                            ?: return@put call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                    val kind =
                        SynapseKind.fromWire(call.parameters["kind"])
                            ?: return@put call.respond(HttpStatusCode.NotFound, errorBody("unknown sidecar kind"))
                    val owner = call.owner(db) ?: return@put
                    if (!withContext(Dispatchers.IO) { consentStore.isGranted(owner, kind.scope) }) {
                        return@put call.respond(
                            HttpStatusCode.Forbidden,
                            errorBody("${kind.scope} consent not granted"),
                        )
                    }
                    if (withContext(Dispatchers.IO) { store.deleted(owner, clientId, kind) }) {
                        return@put call.respond(HttpStatusCode.Gone, errorBody("${kind.wireName} data was deleted"))
                    }
                    if (withContext(Dispatchers.IO) { store.activityId(owner, clientId) } == null) {
                        return@put call.respond(HttpStatusCode.NotFound, errorBody("the activity does not exist"))
                    }
                    val limit = if (kind == SynapseKind.RAW) MAX_RAW_SIDECAR_BYTES else MAX_FEATURE_SIDECAR_BYTES
                    val declared = call.request.contentLength()
                    if (declared != null && declared > limit) {
                        return@put call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            errorBody("${kind.wireName} file too large"),
                        )
                    }
                    withContext(Dispatchers.IO) {
                        val dir = Files.createTempDirectory("averyn-synapse-")
                        try {
                            val file = dir.resolve("$clientId.${kind.wireName}.bin")
                            var sha256 =
                                call.receiveToFile(file, limit)
                                    ?: return@withContext call.respond(
                                        HttpStatusCode.PayloadTooLarge,
                                        errorBody("file too large"),
                                    )
                            var bytes = Files.readAllBytes(file)
                            // A whole record at least; a cut tail (the app was killed mid-write) is allowed and ignored.
                            if (readSynapseSidecar(bytes).records.isEmpty()) {
                                return@withContext call.respond(
                                    HttpStatusCode.UnprocessableEntity,
                                    errorBody("the sidecar has no whole record"),
                                )
                            }
                            if (kind == SynapseKind.FEATURES) {
                                // signal_quality lets the sidecar in; stress, RR and the band's HRV are separate
                                // consents (ADR-0021), so the fields of a scope not granted are never written.
                                val ungranted = ungrantedFields { consentStore.isGranted(owner, it) }
                                if (ungranted.isNotEmpty()) {
                                    bytes = maskFeatureSidecar(bytes, ungranted)
                                    Files.write(file, bytes)
                                    sha256 = sha256Hex(bytes)
                                }
                            }
                            val previous = store.get(owner, clientId, kind)
                            val stored = store.storedBytes(owner) - (previous?.sizeBytes ?: 0L)
                            if (stored + bytes.size > quotaBytes) {
                                return@withContext call.respond(
                                    HttpStatusCode.InsufficientStorage,
                                    errorBody("storage quota exceeded"),
                                )
                            }
                            val key = store.keyOf(owner, clientId, kind, sha256)
                            raw.put(key, file)
                            if (!store.record(owner, clientId, kind, StoredSidecar(key, sha256, bytes.size.toLong()))) {
                                raw.delete(key)
                                return@withContext call.respond(
                                    HttpStatusCode.NotFound,
                                    errorBody("the activity does not exist"),
                                )
                            }
                            if (previous != null && previous.objectKey != key) raw.delete(previous.objectKey)
                            call.respond(HttpStatusCode.NoContent)
                        } finally {
                            dir.toFile().deleteRecursively()
                        }
                    }
                }

                get("/v1/activities/{clientActivityId}/synapse") {
                    val clientId =
                        call.uuid("clientActivityId")
                            ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                    val owner = call.owner(db) ?: return@get
                    val body =
                        withContext(Dispatchers.IO) {
                            val features =
                                store.get(owner, clientId, SynapseKind.FEATURES)?.takeIf {
                                    consentStore.isGranted(owner, ConsentScope.SIGNAL_QUALITY)
                                }
                            val rawStored =
                                store.get(owner, clientId, SynapseKind.RAW)?.takeIf {
                                    consentStore.isGranted(owner, ConsentScope.RAW_SIGNALS)
                                }
                            if (features == null &&
                                rawStored == null &&
                                store.activityId(owner, clientId) == null
                            ) {
                                return@withContext null
                            }
                            SynapseSummaryDto(
                                clientActivityId = clientId.toString(),
                                features =
                                    features?.let {
                                        buildFeatures(
                                            raw.get(it.objectKey),
                                            FeatureConsent(
                                                stress = consentStore.isGranted(owner, ConsentScope.STRESS_MONITORING),
                                                rr = consentStore.isGranted(owner, ConsentScope.RR_RECORDING),
                                                bandHrv = consentStore.isGranted(owner, ConsentScope.HRV_ANALYSIS),
                                            ),
                                        )
                                    },
                                raw = rawStored?.let { recordingOf(it).summary() },
                            )
                        }
                    if (body == null) {
                        call.respond(HttpStatusCode.NotFound, errorBody("the activity does not exist"))
                    } else {
                        call.respond(body)
                    }
                }

                get("/v1/activities/{clientActivityId}/synapse/raw/{stream}") {
                    val clientId =
                        call.uuid("clientActivityId")
                            ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                    val stream =
                        SynapseRawStream.entries.firstOrNull { it.wireName == call.parameters["stream"] }
                            ?: return@get call.respond(HttpStatusCode.NotFound, errorBody("unknown stream"))
                    val owner = call.owner(db) ?: return@get
                    val from = call.request.queryParameters["from"]?.toLongOrNull() ?: 0L
                    val to = call.request.queryParameters["to"]?.toLongOrNull() ?: Long.MAX_VALUE
                    val points =
                        (call.request.queryParameters["maxPoints"]?.toIntOrNull() ?: DEFAULT_WINDOW_POINTS)
                            .coerceIn(
                                2,
                                MAX_WINDOW_POINTS,
                            )
                    if (to <= from) return@get call.respond(HttpStatusCode.BadRequest, errorBody("empty window"))
                    val window =
                        withContext(Dispatchers.IO) {
                            if (!consentStore.isGranted(owner, ConsentScope.RAW_SIGNALS)) return@withContext null
                            store
                                .get(
                                    owner,
                                    clientId,
                                    SynapseKind.RAW,
                                )?.let { recordingOf(it).window(stream, from, to, points) }
                        }
                    if (window ==
                        null
                    ) {
                        call.respond(HttpStatusCode.NotFound, errorBody("no such signal"))
                    } else {
                        call.respond(window)
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.uuid(name: String): UUID? =
    parameters[name]?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
    }
