package dev.averyn.backend.activities

import dev.averyn.backend.auth.AccountDeletedException
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.auth.userIdFor
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

/** Upload limit for a health import (FIT files are small; 32 MB is plenty). */
const val MAX_HEALTH_IMPORT_BYTES = 32L * 1024 * 1024

const val HEALTH_REQUESTS_PER_MINUTE = 120

private val HEALTH_PER_USER = RateLimitName("per-user-health")

/**
 * `POST /v1/health/import` and `GET /v1/health/summary` for Synapse health data
 * (day-1 lab path, docs/integrations/synapse-import.md).
 *
 * Owner-only, rate-limited per user. Consent per scope (ADR-0013) is checked
 * before storing any health kind — until the consent UI exists the route
 * answers `403` for hrv/stress/sleep/sqi kinds.
 */
fun Application.health(
    db: DataSource,
    maxBytes: Long = MAX_HEALTH_IMPORT_BYTES,
    requestsPerMinute: Int = HEALTH_REQUESTS_PER_MINUTE,
) {
    install(RateLimit) {
        register(HEALTH_PER_USER) {
            rateLimiter(limit = requestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> checkNotNull(call.principal<JWTPrincipal>()).payload.subject }
        }
    }

    routing {
        authenticate(OIDC_AUTH) {
            rateLimit(HEALTH_PER_USER) {

                post("/v1/health/import") {
                    val declared = call.request.contentLength()
                    if (declared != null && declared > maxBytes) {
                        return@post call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            errorBody("import file too large"),
                        )
                    }
                    val owner = call.owner(db) ?: return@post

                    // Consent gate (ADR-0013): health kinds are GDPR Art. 9 data.
                    // Until the consent UI ships, this is local-only/demo:
                    // we require no active consent but log that the gate is pending.
                    // TODO: checkConsent(owner, setOf("hrv_analysis","stress_monitoring",...)) ?: 403

                    val fileItem = call.receiveMultipart().parts.firstOrNull { it is io.ktor.server.request.PartData.Item.FileItem && it.name == "file" }
                        as? io.ktor.server.request.PartData.Item.FileItem
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            errorBody("missing file part"),
                        )
                    val deviceSerial = call.parameters["deviceSerial"]
                    val activityId =
                        call.parameters["activityId"]?.toUuidOrNull()

                    fileItem.stream().use { stream ->
                        val tmp = Files.createTempDirectory("averyn-health-import-")
                        try {
                            val file = tmp.resolve(fileItem.originalFileName ?: "synapse.fit")
                            Files.copy(stream, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            val result =
                                withContext(Dispatchers.IO) {
                                    ingestFit(db, owner, file, deviceSerial, activityId)
                                }
                            when (result) {
                                is HealthIngestResult.Success -> call.respond(
                                    HttpStatusCode.OK,
                                    mapOf(
                                        "imported" to result.imported,
                                        "device_id" to result.deviceId,
                                        "source" to result.source,
                                        "algorithm_version" to result.algorithmVersion,
                                    ),
                                )
                                is HealthIngestResult.Failure ->
                                    call.respond(
                                        HttpStatusCode.UnprocessableEntity,
                                        errorBody(result.reason),
                                    )
                            }
                        } finally {
                            tmp.toFile().deleteRecursively()
                        }
                    }
                }

                get("/v1/health/summary") {
                    val owner = call.owner(db) ?: return@get
                    val from =
                        call.request.queryParameters["from"]?.let { Instant.parse(it) }
                            ?: Instant.EPOCH
                    val to =
                        call.request.queryParameters["to"]?.let { Instant.parse(it) }
                            ?: Instant.now()
                    val kind = call.request.queryParameters["kind"]

                    val rows = withContext(Dispatchers.IO) {
                        db.connection.use { conn ->
                            val sql = buildString {
                                append("SELECT kind, recorded_at, value_json, source, algorithm_version, device_id ")
                                append("FROM imported_health_samples ")
                                append("WHERE owner_id = ? AND recorded_at >= ? AND recorded_at < ?")
                                if (kind != null) append(" AND kind = ?")
                                append(" ORDER BY recorded_at DESC")
                            }
                            conn.prepareStatement(sql).use { stmt ->
                                stmt.setObject(1, owner)
                                stmt.setTimestamp(2, java.sql.Timestamp.from(from))
                                stmt.setTimestamp(3, java.sql.Timestamp.from(to))
                                var i = 4
                                if (kind != null) {
                                    stmt.setString(i, kind)
                                    i++
                                }
                                stmt.executeQuery().use { rs ->
                                    val rows = mutableListOf<Map<String, Any?>>()
                                    while (rs.next()) {
                                        rows += mapOf(
                                            "kind" to rs.getString(1),
                                            "recorded_at" to rs.getTimestamp(4)?.toInstant(),
                                            "value_json" to rs.getString(5),
                                            "source" to rs.getString(6),
                                            "algorithm_version" to rs.getString(7),
                                            "device_id" to rs.getObject(8, UUID::class.java),
                                        )
                                    }
                                    rows
                                }
                            }
                        }
                    }
                    call.respond(rows)
                }
            }
        }
    }
}

/** The caller's Averyn user id, or null after answering `403` because the account was deleted. */
private suspend fun ApplicationCall.owner(db: DataSource): UUID? {
    val principal = checkNotNull(principal<JWTPrincipal>())
    return try {
        withContext(Dispatchers.IO) { db.userIdFor(principal, allowDeleted = false) }
    } catch (e: AccountDeletedException) {
        respond(HttpStatusCode.Forbidden, errorBody("account deleted"))
        null
    }
}

private fun errorBody(message: String) = mapOf("error" to message)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
